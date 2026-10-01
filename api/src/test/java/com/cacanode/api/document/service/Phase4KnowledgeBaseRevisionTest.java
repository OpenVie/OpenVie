package com.cacanode.api.document.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import com.cacanode.api.document.messaging.DocumentIngestionPublisher;
import com.cacanode.api.document.messaging.DocumentStatusEvent;
import com.cacanode.api.document.model.Document;
import com.cacanode.api.document.repository.DocumentRepository;
import com.cacanode.api.common.storage.DocumentStorage;
import com.cacanode.api.tenant.api.TenantWorkspaceApi;

class Phase4KnowledgeBaseRevisionTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentStorage documentStorage = mock(DocumentStorage.class);
    private final DocumentIndexCleanup indexCleanup = mock(DocumentIndexCleanup.class);
    private final TenantWorkspaceApi workspaceApi = mock(TenantWorkspaceApi.class);
    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository,
                workspaceApi,
                documentStorage,
                mock(DocumentIngestionPublisher.class),
                indexCleanup,
                mock(ApplicationEventPublisher.class)
        );
    }

    @Test
    void completedStatusTransitionIncrementsRevisionOnce() {
        Document document = document(DocumentStatus.PROCESSING);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));
        DocumentStatusEvent event = new DocumentStatusEvent(
                "1.0", UUID.randomUUID(), UUID.randomUUID(), tenantId, documentId,
                "COMPLETED", 3, null
        );

        service.applyStatusEvent(event);
        service.applyStatusEvent(event);

        verify(workspaceApi).incrementSearchRevision(tenantId, knowledgeBaseId);
    }

    @Test
    void successfulDeletionIncrementsAfterExternalCleanup() {
        Document document = document(DocumentStatus.COMPLETED);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));

        service.delete(tenantId, actorId, true, documentId);

        verify(indexCleanup).delete(tenantId, knowledgeBaseId, documentId);
        verify(documentStorage).delete("storage-key");
        verify(documentRepository).delete(document);
        verify(workspaceApi).incrementSearchRevision(tenantId, knowledgeBaseId);
    }

    @Test
    void failedCleanupDoesNotAdvanceSpringRevision() {
        Document document = document(DocumentStatus.COMPLETED);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId))
                .thenReturn(Optional.of(document));
        doThrow(new IllegalStateException("cleanup failed"))
                .when(indexCleanup).delete(tenantId, knowledgeBaseId, documentId);

        try {
            service.delete(tenantId, actorId, true, documentId);
        } catch (IllegalStateException ignored) {
            // Expected: FastAPI cleanup owns the partial-attempt revision bump.
        }

        verify(workspaceApi, never()).incrementSearchRevision(any(), any());
    }

    private Document document(DocumentStatus status) {
        Document document = new Document();
        document.setId(documentId);
        document.setTenantId(tenantId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setStatus(status);
        document.setStoragePath("storage-key");
        document.setJobId(UUID.randomUUID().toString());
        document.setFileName("source.txt");
        document.setFileType(DocumentType.TXT);
        document.setFileSizeBytes(10L);
        return document;
    }
}
