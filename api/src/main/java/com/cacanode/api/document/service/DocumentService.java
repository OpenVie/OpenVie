package com.cacanode.api.document.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.OptionalLong;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.AccessDeniedException;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.InternalServerErrorException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.cache.BusinessCache;
import com.cacanode.api.common.cache.BusinessCacheInvalidationPublisher;
import com.cacanode.api.common.cache.DocumentListGenerationStore;
import com.cacanode.api.common.cache.VersionedJsonCache;
import com.cacanode.api.common.event.durable.DurableEventPublisher;
import com.cacanode.api.document.cache.DocumentListCacheKeyFactory;
import com.cacanode.api.document.cache.DocumentListCacheValue;
import com.cacanode.api.document.dto.DocumentListItemResponse;
import com.cacanode.api.document.dto.DocumentDownloadResponse;
import com.cacanode.api.document.dto.DocumentStatusResponse;
import com.cacanode.api.document.dto.DocumentUploadResponse;
import com.cacanode.api.document.event.FailedDocumentCleanupRequestedEvent;
import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import com.cacanode.api.document.messaging.DocumentIngestRequestedEvent;
import com.cacanode.api.document.messaging.DocumentIngestionPublisher;
import com.cacanode.api.document.messaging.DocumentStatusEvent;
import com.cacanode.api.document.model.Document;
import com.cacanode.api.document.repository.DocumentRepository;
import com.cacanode.api.common.storage.DocumentStorage;
import com.cacanode.api.tenant.api.TenantWorkspaceApi;

import org.springframework.beans.factory.annotation.Autowired;

@Service
public class DocumentService {

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private final DocumentRepository documentRepository;
    private final TenantWorkspaceApi tenantWorkspaceApi;
    private final DocumentStorage documentStorage;
    private final DocumentIngestionPublisher ingestionPublisher;
    private final DocumentIndexCleanup indexCleanup;
    private final ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private VersionedJsonCache businessCache;
    @Autowired(required = false)
    private DocumentListGenerationStore generationStore;
    @Autowired(required = false)
    private DocumentListCacheKeyFactory documentCacheKeyFactory;
    @Autowired(required = false)
    private BusinessCacheInvalidationPublisher businessInvalidationPublisher;
    @Autowired(required = false)
    private DurableEventPublisher durableEventPublisher;

    @Autowired
    public DocumentService(
            DocumentRepository documentRepository,
            TenantWorkspaceApi tenantWorkspaceApi,
            DocumentStorage documentStorage,
            DocumentIngestionPublisher ingestionPublisher,
            DocumentIndexCleanup indexCleanup,
            ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.tenantWorkspaceApi = tenantWorkspaceApi;
        this.documentStorage = documentStorage;
        this.ingestionPublisher = ingestionPublisher;
        this.indexCleanup = indexCleanup;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(noRollbackFor = InternalServerErrorException.class)
    public DocumentUploadResponse upload(UUID tenantId, UUID userId, UUID knowledgeBaseId, MultipartFile file) {
        try {
            tenantWorkspaceApi.requireActiveKnowledgeBase(tenantId, knowledgeBaseId);
        } catch (RuntimeException exception) {
            throw new BadRequestException("Knowledge base is not active or not found");
        }

        validateFile(file);

        String safeFileName = safeFileName(file.getOriginalFilename());
        DocumentType documentType = documentTypeFor(safeFileName);
        UUID jobId = UUID.randomUUID();

        Document document = new Document();
        document.setTenantId(tenantId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setUploadedBy(userId);
        document.setFileName(safeFileName);
        document.setFileType(documentType);
        document.setFileSizeBytes(file.getSize());
        document.setStoragePath("pending");
        document.setStatus(DocumentStatus.PENDING);
        document.setJobId(jobId.toString());

        document = documentRepository.save(document);
        String storageKey = storageKey(tenantId, knowledgeBaseId, document.getId(), safeFileName);
        document.setStoragePath(storageKey);

        try {
            documentStorage.store(storageKey, file);
        } catch (RuntimeException e) {
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage("DOCUMENT_STORAGE_FAILED");
            invalidateDocuments(tenantId, knowledgeBaseId);
            if (e instanceof InternalServerErrorException) {
                throw e;
            }
            throw new InternalServerErrorException("Unable to store uploaded document");
        }

        DocumentIngestRequestedEvent event = new DocumentIngestRequestedEvent(
                "1.0",
                UUID.randomUUID(),
                jobId,
                tenantId,
                knowledgeBaseId,
                document.getId(),
                userId,
                storageKey,
                safeFileName,
                file.getContentType(),
                file.getSize(),
                Instant.now()
        );

        try {
            ingestionPublisher.publish(event);
        } catch (RuntimeException e) {
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage("INGESTION_PUBLISH_FAILED");
            invalidateDocuments(tenantId, knowledgeBaseId);
            if (e instanceof InternalServerErrorException) {
                throw e;
            }
            throw new InternalServerErrorException("Unable to publish document ingestion request");
        }

        invalidateDocuments(tenantId, knowledgeBaseId);
        return toUploadResponse(document);
    }

    /**
     * Deletes a document and its derived indexes. A workspace admin may delete
     * any document in the workspace; a member may delete only their own upload.
     */
    @Transactional
    public void delete(UUID tenantId, UUID actorId, boolean workspaceAdmin, UUID documentId) {
        Document document = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        if (!workspaceAdmin && !actorId.equals(document.getUploadedBy())) {
            throw new AccessDeniedException("You can only delete documents you uploaded");
        }
        if (document.getStatus() == DocumentStatus.PENDING
                || document.getStatus() == DocumentStatus.PROCESSING) {
            throw new BadRequestException("Wait for document processing to finish before deleting it");
        }
        if (document.getStatus() == DocumentStatus.FAILED) {
            documentRepository.delete(document);
            eventPublisher.publishEvent(new FailedDocumentCleanupRequestedEvent(
                    tenantId,
                    document.getKnowledgeBaseId(),
                    documentId,
                    document.getStoragePath()
            ));
            incrementSearchRevision(tenantId, document.getKnowledgeBaseId());
            invalidateDocuments(tenantId, document.getKnowledgeBaseId());
            return;
        }
        indexCleanup.delete(tenantId, document.getKnowledgeBaseId(), documentId);
        documentStorage.delete(document.getStoragePath());
        documentRepository.delete(document);
        incrementSearchRevision(tenantId, document.getKnowledgeBaseId());
        invalidateDocuments(tenantId, document.getKnowledgeBaseId());
    }

    @Transactional(readOnly = true)
    public DocumentStatusResponse get(UUID tenantId, UUID documentId) {
        return documentRepository.findByIdAndTenantId(documentId, tenantId)
                .map(this::toStatusResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    @Transactional(readOnly = true)
    public DocumentDownloadResponse download(UUID tenantId, UUID documentId) {
        Document document = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        try {
            var stored = documentStorage.load(document.getStoragePath());
            String contentType = StringUtils.hasText(stored.contentType())
                    ? stored.contentType()
                    : contentTypeFor(document.getFileType());
            return new DocumentDownloadResponse(
                    document.getFileName(),
                    contentType,
                    stored.content()
            );
        } catch (InternalServerErrorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InternalServerErrorException("Unable to download document", e);
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentListItemResponse> list(UUID tenantId, UUID knowledgeBaseId) {
        if (!documentCachingAvailable()) {
            return loadLegacyList(tenantId, knowledgeBaseId);
        }
        OptionalLong generation = generationStore.current(tenantId, knowledgeBaseId);
        if (generation.isEmpty()) {
            return businessCache.bypassAndLoad(BusinessCache.DOCUMENT_LIST,
                    () -> loadLegacyList(tenantId, knowledgeBaseId));
        }
        var filters = documentCacheKeyFactory.legacy();
        return businessCache.getOrLoad(
                BusinessCache.DOCUMENT_LIST,
                documentCacheKeyFactory.key(tenantId, knowledgeBaseId, generation.getAsLong(), filters),
                DocumentListCacheValue.class,
                () -> new DocumentListCacheValue(loadLegacyList(tenantId, knowledgeBaseId))
        ).documents();
    }

    private List<DocumentListItemResponse> loadLegacyList(UUID tenantId, UUID knowledgeBaseId) {
        requireActiveKnowledgeBase(tenantId, knowledgeBaseId);

        return documentRepository
                .findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, knowledgeBaseId)
                .stream()
                .map(this::toListItemResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentListItemResponse> list(
            UUID tenantId,
            UUID knowledgeBaseId,
            Integer page,
            Integer size,
            String searchText,
            DocumentStatus status,
            DocumentType type
    ) {
        return listResult(tenantId, knowledgeBaseId, page, size, searchText, status, type,
                null, null, null, null).documents();
    }

    @Transactional(readOnly = true)
    public DocumentListResult listResult(
            UUID tenantId,
            UUID knowledgeBaseId,
            Integer page,
            Integer size,
            String searchText,
            DocumentStatus status,
            DocumentType type,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            String sort,
            String direction
    ) {
        int requestedPage = page == null ? 0 : page;
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (requestedPage < 0) {
            throw new BadRequestException("Page must be zero or greater");
        }
        if (requestedSize < 1 || requestedSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("Size must be between 1 and 100");
        }

        String query = StringUtils.hasText(searchText) ? searchText.strip() : null;
        if (query != null && query.length() > MAX_SEARCH_LENGTH) {
            throw new BadRequestException("Search text must be 200 characters or fewer");
        }
        if (uploadedFrom != null && uploadedTo != null && uploadedFrom.isAfter(uploadedTo)) {
            throw new BadRequestException("Upload start date must not be after end date");
        }
        String requestedSort = normalizeDocumentSort(sort);
        String requestedDirection = normalizeDirection(direction);

        if (!documentCachingAvailable()) {
            return loadPagedList(tenantId, knowledgeBaseId, requestedPage, requestedSize, query,
                    status, type, uploadedFrom, uploadedTo, requestedSort,
                    requestedDirection);
        }
        var filters = documentCacheKeyFactory.paged(
                requestedPage, requestedSize, query, status, type, uploadedFrom,
                uploadedTo, requestedSort, requestedDirection);
        OptionalLong generation = generationStore.current(tenantId, knowledgeBaseId);
        if (generation.isEmpty()) {
            return businessCache.bypassAndLoad(BusinessCache.DOCUMENT_LIST,
                    () -> loadPagedList(tenantId, knowledgeBaseId, requestedPage, requestedSize,
                            filters.searchText(), status, type, uploadedFrom, uploadedTo,
                            requestedSort, requestedDirection));
        }
        DocumentListCacheValue cached = businessCache.getOrLoad(
                BusinessCache.DOCUMENT_LIST,
                documentCacheKeyFactory.key(tenantId, knowledgeBaseId, generation.getAsLong(), filters),
                DocumentListCacheValue.class,
                () -> {
                    DocumentListResult loaded = loadPagedList(tenantId, knowledgeBaseId,
                            requestedPage, requestedSize, filters.searchText(), status, type,
                            uploadedFrom, uploadedTo, requestedSort, requestedDirection);
                    return new DocumentListCacheValue(loaded.documents(), loaded.totalCount());
                }
        );
        return new DocumentListResult(cached.documents(), cached.totalCount());
    }

    private DocumentListResult loadPagedList(
            UUID tenantId,
            UUID knowledgeBaseId,
            int requestedPage,
            int requestedSize,
            String query,
            DocumentStatus status,
            DocumentType type,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            String sort,
            String direction
    ) {
        requireActiveKnowledgeBase(tenantId, knowledgeBaseId);

        Specification<Document> specification = (root, criteriaQuery, builder) -> builder.and(
                builder.equal(root.get("tenantId"), tenantId),
                builder.equal(root.get("knowledgeBaseId"), knowledgeBaseId)
        );
        if (query != null) {
            String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.like(builder.lower(root.get("fileName")), pattern, '\\'));
        }
        if (status != null) {
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.equal(root.get("status"), status));
        }
        if (type != null) {
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.equal(root.get("fileType"), type));
        }
        if (uploadedFrom != null) {
            LocalDateTime from = uploadedFrom.atStartOfDay();
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (uploadedTo != null) {
            LocalDateTime until = uploadedTo.plusDays(1).atStartOfDay();
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.lessThan(root.get("createdAt"), until));
        }

        String property = switch (sort) {
            case "filename" -> "fileName";
            case "size" -> "fileSizeBytes";
            default -> "createdAt";
        };
        Sort.Direction sortDirection = "asc".equals(direction)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(
                requestedPage,
                requestedSize,
                Sort.by(new Sort.Order(sortDirection, property), new Sort.Order(sortDirection, "id"))
        );
        Page<Document> result = documentRepository.findAll(specification, pageable);
        List<DocumentListItemResponse> documents = result.getContent().stream()
                .map(this::toListItemResponse)
                .toList();
        return new DocumentListResult(documents, result.getTotalElements());
    }

    private String normalizeDocumentSort(String value) {
        if (value == null || value.isBlank() || "uploaded".equalsIgnoreCase(value)
                || "createdAt".equalsIgnoreCase(value)) return "uploaded";
        if ("filename".equalsIgnoreCase(value) || "fileName".equalsIgnoreCase(value)) return "filename";
        if ("size".equalsIgnoreCase(value) || "fileSizeBytes".equalsIgnoreCase(value)) return "size";
        throw new BadRequestException("Sort must be uploaded, filename, or size");
    }

    private String normalizeDirection(String value) {
        if (value == null || value.isBlank() || "desc".equalsIgnoreCase(value)) return "desc";
        if ("asc".equalsIgnoreCase(value)) return "asc";
        throw new BadRequestException("Direction must be asc or desc");
    }

    public record DocumentListResult(List<DocumentListItemResponse> documents, long totalCount) {
        public DocumentListResult {
            documents = List.copyOf(documents);
        }
    }

    @Transactional
    public boolean applyStatusEvent(DocumentStatusEvent event) {
        DocumentStatus status = parseStatus(event.status());
        return documentRepository.findByIdAndTenantId(event.documentId(), event.tenantId())
                .map(document -> {
                    DocumentStatus previousStatus = document.getStatus();
                    if (!isMonotonicTransition(previousStatus, status)) {
                        return true;
                    }
                    document.setStatus(status);
                    document.setChunkCount(event.chunkCount());
                    document.setErrorMessage(event.errorMessage());
                    if (status == DocumentStatus.COMPLETED
                            && previousStatus != DocumentStatus.COMPLETED) {
                        incrementSearchRevision(event.tenantId(), document.getKnowledgeBaseId());
                    }
                    invalidateDocuments(event.tenantId(), document.getKnowledgeBaseId());
                    return true;
                })
                .orElse(false);
    }

    
    private boolean isMonotonicTransition(DocumentStatus current, DocumentStatus next) {
        if (current == next) {
            return false;
        }
        if (current == DocumentStatus.COMPLETED || current == DocumentStatus.FAILED) {
            return false;
        }
        if (current == DocumentStatus.PROCESSING) {
            return next == DocumentStatus.COMPLETED || next == DocumentStatus.FAILED;
        }
        return next == DocumentStatus.PROCESSING
                || next == DocumentStatus.COMPLETED
                || next == DocumentStatus.FAILED;
    }

    private boolean documentCachingAvailable() {
        return businessCache != null && generationStore != null && documentCacheKeyFactory != null;
    }

    private void invalidateDocuments(UUID tenantId, UUID knowledgeBaseId) {
        if (businessInvalidationPublisher != null) {
            businessInvalidationPublisher.documentMutation(tenantId, knowledgeBaseId);
        }
    }

    private void incrementSearchRevision(UUID tenantId, UUID knowledgeBaseId) {
        if (tenantWorkspaceApi != null) {
            tenantWorkspaceApi.incrementSearchRevision(tenantId, knowledgeBaseId);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new BadRequestException("Uploaded file is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BadRequestException("Uploaded file exceeds 20 MB");
        }

        String filename = safeFileName(file.getOriginalFilename());
        DocumentType type = documentTypeFor(filename);
        DocumentFileValidator.validate(file, type);
    }

    private void requireActiveKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        try {
            tenantWorkspaceApi.requireActiveKnowledgeBase(tenantId, knowledgeBaseId);
        } catch (RuntimeException exception) {
            throw new BadRequestException("Knowledge base is not active or not found");
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private DocumentType documentTypeFor(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return DocumentType.TXT;
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return DocumentType.MARKDOWN;
        }
        if (lower.endsWith(".docx")) {
            return DocumentType.DOCX;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return DocumentType.HTML;
        }
        if (lower.endsWith(".xlsx")) {
            return DocumentType.XLSX;
        }
        if (lower.endsWith(".csv")) {
            return DocumentType.CSV;
        }
        if (lower.endsWith(".pdf")) {
            return DocumentType.PDF;
        }
        throw new BadRequestException("Unsupported file extension");
    }

    private String safeFileName(String originalFilename) {
        String cleaned = StringUtils.hasText(originalFilename) ? originalFilename.replace('\\', '/') : "document";
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!StringUtils.hasText(cleaned) || cleaned.equals(".") || cleaned.equals("..")) {
            return "document";
        }
        return cleaned;
    }

    private String storageKey(UUID tenantId, UUID knowledgeBaseId, UUID documentId, String fileName) {
        return "tenants/%s/knowledge-bases/%s/documents/%s/%s"
                .formatted(tenantId, knowledgeBaseId, documentId, fileName);
    }

    private String contentTypeFor(DocumentType type) {
        return switch (type) {
            case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case TXT -> "text/plain";
            case MARKDOWN -> "text/markdown";
            case HTML -> "text/html";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case CSV -> "text/csv";
        };
    }

    private DocumentStatus parseStatus(String status) {
        try {
            return DocumentStatus.valueOf(status);
        } catch (RuntimeException e) {
            throw new BadRequestException("Unsupported document status event");
        }
    }

    private DocumentUploadResponse toUploadResponse(Document document) {
        return new DocumentUploadResponse(
                document.getId(),
                UUID.fromString(document.getJobId()),
                document.getFileName(),
                document.getStatus()
        );
    }

    private DocumentStatusResponse toStatusResponse(Document document) {
        return new DocumentStatusResponse(
                document.getId(),
                UUID.fromString(document.getJobId()),
                document.getFileName(),
                document.getFileType(),
                document.getFileSizeBytes(),
                document.getCreatedAt(),
                document.getKnowledgeBaseId(),
                document.getStatus(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getUploadedBy()
        );
    }

    private DocumentListItemResponse toListItemResponse(Document document) {
        return new DocumentListItemResponse(
                document.getId(),
                UUID.fromString(document.getJobId()),
                document.getFileName(),
                document.getFileType(),
                document.getFileSizeBytes(),
                document.getKnowledgeBaseId(),
                document.getStatus(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUploadedBy()
        );
    }
}
