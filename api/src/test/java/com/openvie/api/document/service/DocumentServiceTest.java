// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.InternalServerErrorException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;
import com.openvie.api.document.event.FailedDocumentCleanupRequestedEvent;
import org.springframework.security.access.AccessDeniedException;
import com.openvie.api.document.messaging.DocumentIngestRequestedEvent;
import com.openvie.api.document.messaging.DocumentIngestionPublisher;
import com.openvie.api.document.messaging.DocumentStatusEvent;
import com.openvie.api.document.model.Document;
import com.openvie.api.document.repository.DocumentRepository;
import com.openvie.api.common.storage.DocumentStorage;
import com.openvie.api.common.storage.StoredDocument;
import com.openvie.api.tenant.api.TenantWorkspaceApi;

class DocumentServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    /** The account that uploaded the fixture document; see document(). */
    private final UUID userId = UUID.randomUUID();
    private final UUID uploaderId = userId;
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private TenantWorkspaceApi tenantWorkspaceApi;

    @Mock
    private DocumentStorage documentStorage;

    @Mock
    private DocumentIngestionPublisher ingestionPublisher;

    @Mock
    private DocumentIndexCleanup indexCleanup;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AutoCloseable mocks;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        documentService = new DocumentService(
                documentRepository,
                tenantWorkspaceApi,
                documentStorage,
                ingestionPublisher,
                indexCleanup,
                eventPublisher
        );

        when(tenantWorkspaceApi.requireActiveKnowledgeBase(tenantId, knowledgeBaseId))
                .thenReturn(new TenantWorkspaceApi.WorkspaceContext(
                        tenantId, null, knowledgeBaseId, "Tenant", 0));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(documentId);
            return document;
        });
    }

    @Test
    void uploadStoresPendingDocumentAndPublishesEvent() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "return-policy-demo-vi.pdf",
                "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes()
        );

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals(documentId, response.id());
        assertEquals("return-policy-demo-vi.pdf", response.fileName());
        assertEquals(DocumentStatus.PENDING, response.status());

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(documentCaptor.capture());
        Document saved = documentCaptor.getValue();
        assertEquals(tenantId, saved.getTenantId());
        assertEquals(knowledgeBaseId, saved.getKnowledgeBaseId());
        assertEquals(userId, saved.getUploadedBy());
        assertEquals(DocumentType.PDF, saved.getFileType());
        assertEquals(DocumentStatus.PENDING, saved.getStatus());
        assertTrue(saved.getStoragePath().contains("/documents/" + documentId + "/return-policy-demo-vi.pdf"));

        verify(documentStorage).store(saved.getStoragePath(), file);

        ArgumentCaptor<DocumentIngestRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(DocumentIngestRequestedEvent.class);
        verify(ingestionPublisher).publish(eventCaptor.capture());
        DocumentIngestRequestedEvent event = eventCaptor.getValue();
        assertEquals("1.0", event.schemaVersion());
        assertEquals(tenantId, event.tenantId());
        assertEquals(knowledgeBaseId, event.knowledgeBaseId());
        assertEquals(documentId, event.documentId());
        assertEquals(userId, event.uploaderId());
        assertEquals(saved.getStoragePath(), event.storageKey());
    }

    @Test
    void uploadRejectsInactiveKnowledgeBase() {
        doThrow(new BadRequestException("inactive")).when(tenantWorkspaceApi)
                .requireActiveKnowledgeBase(tenantId, knowledgeBaseId);

        assertThrows(BadRequestException.class, () -> documentService.upload(
                tenantId,
                userId,
                knowledgeBaseId,
                txtFile()
        ));

        verify(documentRepository, never()).save(any());
    }

    @Test
    void uploadRejectsWrongTenantKnowledgeBase() {
        doThrow(new BadRequestException("missing")).when(tenantWorkspaceApi)
                .requireActiveKnowledgeBase(tenantId, knowledgeBaseId);

        assertThrows(BadRequestException.class, () -> documentService.upload(
                tenantId,
                userId,
                knowledgeBaseId,
                txtFile()
        ));
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.txt", "text/plain", new byte[0]);

        assertThrows(BadRequestException.class, () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
    }

    @Test
    void uploadRejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.doc", "application/msword", "x".getBytes());

        assertThrows(BadRequestException.class, () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
    }

    @Test
    void uploadRejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.txt", "text/csv", "x".getBytes());

        assertThrows(BadRequestException.class, () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
    }

    @Test
    void uploadRejectsImageWhenOcrGateDisabled() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.png", "image/png", pngBytes());

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        assertEquals("Image ingestion is not enabled", exception.getMessage());
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadStoresImageDocumentWhenOcrGateEnabled() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "imageUploadsEnabled", true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.png", "image/png", pngBytes());

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals("scan.png", response.fileName());
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(documentCaptor.capture());
        assertEquals(DocumentType.IMAGE, documentCaptor.getValue().getFileType());

        ArgumentCaptor<DocumentIngestRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(DocumentIngestRequestedEvent.class);
        verify(ingestionPublisher).publish(eventCaptor.capture());
        assertEquals("image/png", eventCaptor.getValue().contentType());
    }

    @Test
    void uploadRejectsImageWithInvalidSignature() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "imageUploadsEnabled", true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.png", "image/png", "definitely not an image".getBytes());

        assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadRejectsAudioWhenAsrGateDisabled() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "briefing.mp3", "audio/mpeg", mp3Bytes());

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        assertEquals("Audio ingestion is not enabled", exception.getMessage());
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadStoresAudioDocumentWhenAsrGateEnabled() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "audioUploadsEnabled", true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "briefing.mp3", "audio/mpeg", mp3Bytes());

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals("briefing.mp3", response.fileName());
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(documentCaptor.capture());
        assertEquals(DocumentType.AUDIO, documentCaptor.getValue().getFileType());

        ArgumentCaptor<DocumentIngestRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(DocumentIngestRequestedEvent.class);
        verify(ingestionPublisher).publish(eventCaptor.capture());
        assertEquals("audio/mpeg", eventCaptor.getValue().contentType());
    }

    @Test
    void uploadRejectsAudioWithInvalidSignature() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "audioUploadsEnabled", true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "briefing.mp3", "audio/mpeg", "definitely not audio".getBytes());

        assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadRejectsVideoWhenVideoGateDisabled() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", mp4Bytes());

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        assertEquals("Video ingestion is not enabled", exception.getMessage());
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadStoresVideoDocumentWhenVideoGateEnabled() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "videoUploadsEnabled", true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", mp4Bytes());

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals("clip.mp4", response.fileName());
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(documentCaptor.capture());
        assertEquals(DocumentType.VIDEO, documentCaptor.getValue().getFileType());

        ArgumentCaptor<DocumentIngestRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(DocumentIngestRequestedEvent.class);
        verify(ingestionPublisher).publish(eventCaptor.capture());
        assertEquals("video/mp4", eventCaptor.getValue().contentType());
    }

    @Test
    void uploadRejectsVideoWithInvalidSignature() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "videoUploadsEnabled", true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", "definitely not video".getBytes());

        assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadRejectsFileExceedingConfigurableSize() {
        org.springframework.test.util.ReflectionTestUtils.setField(documentService, "maxFileSizeMb", 5L);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(5L * 1024L * 1024L + 1L);
        when(file.getOriginalFilename()).thenReturn("doc.txt");

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
        assertEquals("Uploaded file exceeds 5 MB", exception.getMessage());
    }

    private static byte[] mp3Bytes() {
        byte[] bytes = new byte[32];
        bytes[0] = 'I';
        bytes[1] = 'D';
        bytes[2] = '3';
        return bytes;
    }

    private static byte[] mp4Bytes() {
        byte[] bytes = new byte[32];
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        return bytes;
    }

    private static byte[] pngBytes() {
        // Magic header (validated) plus filler; the validator checks the
        // leading signature only.
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = '\r';
        bytes[5] = '\n';
        bytes[6] = 0x1A;
        bytes[7] = '\n';
        bytes[8] = 'I';
        bytes[9] = 'H';
        bytes[10] = 'D';
        bytes[11] = 'R';
        return bytes;
    }

    @Test
    void uploadRejectsOverTwentyMb() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(20L * 1024L * 1024L + 1L);
        when(file.getOriginalFilename()).thenReturn("doc.txt");

        assertThrows(BadRequestException.class, () -> documentService.upload(tenantId, userId, knowledgeBaseId, file));
    }

    @Test
    void uploadMarksDocumentFailedWhenPublishFails() {
        doThrow(new InternalServerErrorException("publish failed"))
                .when(ingestionPublisher)
                .publish(any(DocumentIngestRequestedEvent.class));

        assertThrows(InternalServerErrorException.class, () -> documentService.upload(
                tenantId,
                userId,
                knowledgeBaseId,
                txtFile()
        ));

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(documentCaptor.capture());
        Document saved = documentCaptor.getValue();
        assertEquals(DocumentStatus.FAILED, saved.getStatus());
        assertEquals("INGESTION_PUBLISH_FAILED", saved.getErrorMessage());
    }

    @Test
    void getRejectsOtherTenantDocument() {
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> documentService.get(tenantId, documentId));
    }

    @Test
    void getIncludesDocumentViewerMetadata() {
        Document document = document();
        LocalDateTime uploadedAt = LocalDateTime.of(2026, 7, 14, 9, 30);
        document.setCreatedAt(uploadedAt);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));

        var response = documentService.get(tenantId, documentId);

        assertEquals(DocumentType.TXT, response.fileType());
        assertEquals(5L, response.fileSizeBytes());
        assertEquals(uploadedAt, response.uploadedAt());
    }

    @Test
    void downloadReturnsTenantScopedOriginalFile() {
        Document document = document();
        byte[] content = "hello".getBytes();
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(documentStorage.load("storage-key"))
                .thenReturn(new StoredDocument(content, "text/x-original"));

        var response = documentService.download(tenantId, documentId);

        assertEquals("notes.txt", response.fileName());
        assertEquals("text/x-original", response.contentType());
        assertArrayEquals(content, response.content());
    }

    @Test
    void downloadRejectsMissingOrOtherTenantDocument() {
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> documentService.download(tenantId, documentId));
        verify(documentStorage, never()).load(any());
    }

    @Test
    void downloadReportsStorageFailure() {
        Document document = document();
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(documentStorage.load("storage-key"))
                .thenThrow(new InternalServerErrorException("storage unavailable"));

        assertThrows(InternalServerErrorException.class,
                () -> documentService.download(tenantId, documentId));
    }

    @Test
    void listReturnsTenantKnowledgeBaseDocumentsNewestFirst() {
        Document document = document();
        when(documentRepository.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(
                tenantId, knowledgeBaseId))
                .thenReturn(List.of(document));

        var response = documentService.list(tenantId, knowledgeBaseId);

        assertEquals(1, response.size());
        assertEquals(documentId, response.get(0).id());
        assertEquals(knowledgeBaseId, response.get(0).knowledgeBaseId());
        assertEquals("notes.txt", response.get(0).fileName());
        assertEquals(DocumentType.TXT, response.get(0).fileType());
        assertEquals(DocumentStatus.PENDING, response.get(0).status());
    }

    @Test
    void listRejectsWrongTenantKnowledgeBase() {
        doThrow(new BadRequestException("missing")).when(tenantWorkspaceApi)
                .requireActiveKnowledgeBase(tenantId, knowledgeBaseId);

        assertThrows(BadRequestException.class, () -> documentService.list(tenantId, knowledgeBaseId));
    }

    @SuppressWarnings("unchecked")
    @Test
    void pagedListUsesRequestedPageSizeAndDeterministicOrdering() {
        Document document = document();
        when(documentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(document)));

        var response = documentService.list(
                tenantId,
                knowledgeBaseId,
                2,
                20,
                " notes ",
                DocumentStatus.PENDING,
                DocumentType.TXT
        );

        assertEquals(1, response.size());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(2, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals("createdAt: DESC,id: DESC", pageable.getSort().toString());
    }

    @Test
    void pagedListRejectsBoundsAndOverlongTrimmedSearch() {
        assertThrows(BadRequestException.class, () -> documentService.list(
                tenantId, knowledgeBaseId, -1, 20, null, null, null));
        assertThrows(BadRequestException.class, () -> documentService.list(
                tenantId, knowledgeBaseId, 0, 0, null, null, null));
        assertThrows(BadRequestException.class, () -> documentService.list(
                tenantId, knowledgeBaseId, 0, 101, null, null, null));
        assertThrows(BadRequestException.class, () -> documentService.list(
                tenantId, knowledgeBaseId, 0, 20, "x".repeat(201), null, null));
    }

    @Test
    void statusEventUpdatesDocument() {
        Document document = document();
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));

        boolean updated = documentService.applyStatusEvent(new DocumentStatusEvent(
                "1.0",
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenantId,
                documentId,
                "COMPLETED",
                12,
                null,
                "ocr",
                4
        ));

        assertTrue(updated);
        assertEquals(DocumentStatus.COMPLETED, document.getStatus());
        assertEquals(12, document.getChunkCount());
        assertEquals("ocr", document.getTextSource());
        assertEquals(4, document.getPageCount());
    }

    @Test
    void statusEventUpdatesFailureMessage() {
        Document document = document();
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));

        documentService.applyStatusEvent(new DocumentStatusEvent(
                "1.0",
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenantId,
                documentId,
                "FAILED",
                null,
                "parse error",
                null,
                null
        ));

        assertEquals(DocumentStatus.FAILED, document.getStatus());
        assertEquals("parse error", document.getErrorMessage());
    }

    @Test
    void tenantAdminDeletesIndexesStorageAndDatabase() {
        Document document = document();
        document.setStatus(DocumentStatus.COMPLETED);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));

        documentService.delete(tenantId, uploaderId, true, documentId);

        verify(indexCleanup).delete(tenantId, knowledgeBaseId, documentId);
        verify(documentStorage).delete("storage-key");
        verify(documentRepository).delete(document);
    }

    @Test
    void tenantAdminDeletesFailedDocumentWithoutWaitingForIndexCleanup() {
        Document document = document();
        document.setStatus(DocumentStatus.FAILED);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));

        documentService.delete(tenantId, uploaderId, true, documentId);

        verify(indexCleanup, never()).delete(any(), any(), any());
        verify(documentStorage, never()).delete(any());
        verify(documentRepository).delete(document);
        verify(eventPublisher).publishEvent(any(FailedDocumentCleanupRequestedEvent.class));
    }

    @Test
    void deleteRejectsProcessingDocumentAndRegularEmployee() {
        Document document = document();
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));

        assertThrows(AccessDeniedException.class,
                () -> documentService.delete(tenantId, otherUserId, false, documentId));
        assertThrows(BadRequestException.class,
                () -> documentService.delete(tenantId, uploaderId, true, documentId));
        verify(indexCleanup, never()).delete(any(), any(), any());
    }

    @Test
    void memberDeletesOwnCompletedDocument() {
        Document document = document();
        document.setStatus(DocumentStatus.COMPLETED);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));

        documentService.delete(tenantId, uploaderId, false, documentId);

        verify(indexCleanup).delete(tenantId, knowledgeBaseId, documentId);
        verify(documentStorage).delete("storage-key");
        verify(documentRepository).delete(document);
    }
    @Test
    void uploadSameFileContentWhenCompletedDoesNothingAndReturnsExisting() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());
        Document existing = document();
        existing.setStatus(DocumentStatus.COMPLETED);
        existing.setContentHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        when(documentRepository.findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
                tenantId, knowledgeBaseId, "notes.txt"))
                .thenReturn(Optional.of(existing));

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals(documentId, response.id());
        assertEquals(DocumentStatus.COMPLETED, response.status());
        verify(documentStorage, never()).store(any(), any(MultipartFile.class));
        verify(ingestionPublisher, never()).publish(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void uploadModifiedFileContentWhenCompletedUpdatesExistingAndPublishesEvent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "updated content".getBytes());
        Document existing = document();
        existing.setStatus(DocumentStatus.COMPLETED);
        existing.setContentHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        when(documentRepository.findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
                tenantId, knowledgeBaseId, "notes.txt"))
                .thenReturn(Optional.of(existing));
        when(documentRepository.save(existing)).thenReturn(existing);

        var response = documentService.upload(tenantId, otherUserId, knowledgeBaseId, file);

        assertEquals(documentId, response.id());
        assertEquals(DocumentStatus.PENDING, response.status());
        assertEquals(DocumentStatus.PENDING, existing.getStatus());
        assertEquals(otherUserId, existing.getUploadedBy());
        assertEquals((long) "updated content".getBytes().length, existing.getFileSizeBytes());
        verify(documentStorage).store("storage-key", file);
        verify(ingestionPublisher).publish(any());
    }

    @Test
    void uploadSameFileContentWhenProcessingDoesNothingAndReturnsExisting() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());
        Document existing = document();
        existing.setStatus(DocumentStatus.PROCESSING);
        existing.setContentHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        when(documentRepository.findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
                tenantId, knowledgeBaseId, "notes.txt"))
                .thenReturn(Optional.of(existing));

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals(documentId, response.id());
        assertEquals(DocumentStatus.PROCESSING, response.status());
        verify(documentStorage, never()).store(any(), any(MultipartFile.class));
        verify(ingestionPublisher, never()).publish(any());
    }

    @Test
    void uploadDifferentFileContentWhenProcessingThrowsBadRequest() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "changed while processing".getBytes());
        Document existing = document();
        existing.setStatus(DocumentStatus.PROCESSING);
        existing.setContentHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        when(documentRepository.findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
                tenantId, knowledgeBaseId, "notes.txt"))
                .thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class, () -> documentService.upload(
                tenantId, userId, knowledgeBaseId, file));
        verify(documentStorage, never()).store(any(), any(MultipartFile.class));
        verify(ingestionPublisher, never()).publish(any());
    }

    @Test
    void uploadSameFileWhenFailedReingestsDocument() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());
        Document existing = document();
        existing.setStatus(DocumentStatus.FAILED);
        existing.setContentHash("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        when(documentRepository.findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
                tenantId, knowledgeBaseId, "notes.txt"))
                .thenReturn(Optional.of(existing));
        when(documentRepository.save(existing)).thenReturn(existing);

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals(documentId, response.id());
        assertEquals(DocumentStatus.PENDING, response.status());
        assertEquals(DocumentStatus.PENDING, existing.getStatus());
        verify(documentStorage).store("storage-key", file);
        verify(ingestionPublisher).publish(any());
    }

    @Test
    void uploadBackfillsContentHashFromStorageForLegacyDocument() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());
        Document existing = document();
        existing.setStatus(DocumentStatus.COMPLETED);
        existing.setContentHash(null);
        existing.setFileSizeBytes(5L);
        when(documentRepository.findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
                tenantId, knowledgeBaseId, "notes.txt"))
                .thenReturn(Optional.of(existing));
        when(documentStorage.load("storage-key"))
                .thenReturn(new StoredDocument("hello".getBytes(), "text/plain"));

        var response = documentService.upload(tenantId, userId, knowledgeBaseId, file);

        assertEquals(documentId, response.id());
        assertEquals(DocumentStatus.COMPLETED, response.status());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", existing.getContentHash());
        verify(documentStorage, never()).store(any(), any(MultipartFile.class));
        verify(ingestionPublisher, never()).publish(any());
    }

    private MockMultipartFile txtFile() {
        return new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
    }

    private Document document() {
        Document document = new Document();
        document.setId(documentId);
        document.setTenantId(tenantId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setUploadedBy(userId);
        document.setFileName("notes.txt");
        document.setFileType(DocumentType.TXT);
        document.setFileSizeBytes(5L);
        document.setStoragePath("storage-key");
        document.setStatus(DocumentStatus.PENDING);
        document.setJobId(UUID.randomUUID().toString());
        return document;
    }
}
