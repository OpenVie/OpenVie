package com.cacanode.api.document.controller;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.document.dto.DocumentListItemResponse;
import com.cacanode.api.document.dto.DocumentStatusResponse;
import com.cacanode.api.document.dto.DocumentUploadResponse;
import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import jakarta.validation.Valid;
import com.cacanode.api.document.service.DocumentService;
import com.cacanode.api.ai.api.AiInferenceApi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController extends BaseController {

    private final DocumentService documentService;
    private final AiInferenceApi inferenceClient;

    @Autowired
    public DocumentController(DocumentService documentService, AiInferenceApi inferenceClient) {
        this.documentService = documentService;
        this.inferenceClient = inferenceClient;
    }

    public DocumentController(DocumentService documentService) {
        this(documentService, null);
    }

    public List<DocumentListItemResponse> list(
            @RequestParam("knowledgeBaseId") UUID knowledgeBaseId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "status", required = false) DocumentStatus status,
            @RequestParam(value = "type", required = false) DocumentType type,
            HttpServletRequest request
    ) {
        UUID tenantId = getTenantId(request);
        if (page == null && size == null && query == null && status == null && type == null) {
            return documentService.list(tenantId, knowledgeBaseId);
        }
        return documentService.list(
                tenantId,
                knowledgeBaseId,
                page,
                size,
                query,
                status,
                type
        );
    }

    @GetMapping
    public ResponseEntity<List<DocumentListItemResponse>> listResponse(
            @RequestParam("knowledgeBaseId") UUID knowledgeBaseId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "status", required = false) DocumentStatus status,
            @RequestParam(value = "type", required = false) DocumentType type,
            @RequestParam(value = "uploaded_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedFrom,
            @RequestParam(value = "uploaded_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedTo,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "direction", required = false) String direction,
            HttpServletRequest request
    ) {
        UUID tenantId = getTenantId(request);
        if (page == null && size == null && query == null && status == null && type == null
                && uploadedFrom == null && uploadedTo == null
                && sort == null && direction == null) {
            List<DocumentListItemResponse> documents = documentService.list(tenantId, knowledgeBaseId);
            return ResponseEntity.ok().header("X-Total-Count", Long.toString(documents.size()))
                    .body(documents);
        }
        var result = documentService.listResult(
                tenantId, knowledgeBaseId, page, size, query, status, type,
                uploadedFrom, uploadedTo, sort, direction);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalCount()))
                .body(result.documents());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseId") UUID knowledgeBaseId,
            HttpServletRequest request
    ) {
        DocumentUploadResponse response = documentService.upload(
                getTenantId(request),
                getUserId(request),
                knowledgeBaseId,
                file
        );
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{documentId}")
    public DocumentStatusResponse get(
            @PathVariable UUID documentId,
            HttpServletRequest request
    ) {
        return documentService.get(getTenantId(request), documentId);
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID documentId,
            HttpServletRequest request
    ) {
        var download = documentService.download(getTenantId(request), documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.content());
    }

    @GetMapping("/{documentId}/units")
    public List<AiInferenceApi.DocumentUnit> units(
            @PathVariable UUID documentId,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            HttpServletRequest request
    ) {
        UUID tenantId = getTenantId(request);
        var document = documentService.get(tenantId, documentId);
        if (inferenceClient == null) {
            throw new IllegalStateException("AI inference client is unavailable");
        }
        return inferenceClient.listDocumentUnits(
                tenantId, document.knowledgeBaseId(), documentId,
                requestId == null ? UUID.randomUUID().toString() : requestId);
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID documentId,
            HttpServletRequest request
    ) {
        documentService.delete(getTenantId(request), getUserId(request),
                isWorkspaceAdmin(request), documentId);
        return ResponseEntity.noContent().build();
    }
}
