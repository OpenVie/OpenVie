package com.cacanode.api.document.listener;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.cacanode.api.document.event.FailedDocumentCleanupRequestedEvent;
import com.cacanode.api.document.service.DocumentIndexCleanup;
import com.cacanode.api.common.storage.DocumentStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailedDocumentCleanupListener {

    private final DocumentIndexCleanup indexCleanup;
    private final DocumentStorage documentStorage;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void cleanup(FailedDocumentCleanupRequestedEvent event) {
        try {
            indexCleanup.delete(event.tenantId(), event.knowledgeBaseId(), event.documentId());
        } catch (RuntimeException e) {
            log.warn("Unable to clean failed document indexes documentId={}", event.documentId(), e);
        }
        try {
            documentStorage.delete(event.storagePath());
        } catch (RuntimeException e) {
            log.warn("Unable to clean failed document storage documentId={}", event.documentId(), e);
        }
    }
}
