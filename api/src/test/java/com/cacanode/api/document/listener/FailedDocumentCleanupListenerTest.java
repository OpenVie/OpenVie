package com.cacanode.api.document.listener;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cacanode.api.document.event.FailedDocumentCleanupRequestedEvent;
import com.cacanode.api.document.service.DocumentIndexCleanup;
import com.cacanode.api.common.storage.DocumentStorage;

class FailedDocumentCleanupListenerTest {

    @Test
    void storageCleanupStillRunsWhenIndexCleanupFails() {
        DocumentIndexCleanup indexCleanup = mock(DocumentIndexCleanup.class);
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        FailedDocumentCleanupListener listener = new FailedDocumentCleanupListener(
                indexCleanup,
                documentStorage
        );
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        doThrow(new RuntimeException("cleanup unavailable"))
                .when(indexCleanup).delete(tenantId, knowledgeBaseId, documentId);

        listener.cleanup(new FailedDocumentCleanupRequestedEvent(
                tenantId,
                knowledgeBaseId,
                documentId,
                "storage-key"
        ));

        verify(documentStorage).delete("storage-key");
    }
}
