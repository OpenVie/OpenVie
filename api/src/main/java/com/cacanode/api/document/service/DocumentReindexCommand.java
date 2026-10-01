package com.cacanode.api.document.service;

import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.messaging.DocumentIngestRequestedEvent;
import com.cacanode.api.document.messaging.DocumentIngestionPublisher;
import com.cacanode.api.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.maintenance.reindex.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DocumentReindexCommand implements ApplicationRunner {
    private final DocumentRepository documentRepository;
    private final DocumentIngestionPublisher publisher;
    private final TransactionTemplate transactions;

    @Value("${app.maintenance.reindex.batch-size:100}")
    private int batchSize;

    @Override
    public void run(ApplicationArguments args) {
        int pageSize = Math.min(Math.max(batchSize, 1), 1000);
        UUID afterId = null;
        long enqueued = 0;
        while (true) {
            UUID cursor = afterId;
            var batch = transactions.execute(status -> (cursor == null
                    ? documentRepository.findByStatusOrderByIdAsc(
                            DocumentStatus.COMPLETED, PageRequest.of(0, pageSize))
                    : documentRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
                            DocumentStatus.COMPLETED, cursor, PageRequest.of(0, pageSize)))
                    .stream()
                    .limit(pageSize)
                    .peek(document -> publisher.publish(new DocumentIngestRequestedEvent(
                            "1.0", UUID.randomUUID(), UUID.randomUUID(), document.getTenantId(),
                            document.getKnowledgeBaseId(), document.getId(), document.getUploadedBy(),
                            document.getStoragePath(), document.getFileName(),
                            "application/octet-stream", document.getFileSizeBytes(), Instant.now())))
                    .toList());
            if (batch == null || batch.isEmpty()) {
                break;
            }
            enqueued += batch.size();
            afterId = batch.get(batch.size() - 1).getId();
            if (batch.size() < pageSize) {
                break;
            }
        }
        log.info("Spring reindex command enqueued {} completed documents", enqueued);
    }
}
