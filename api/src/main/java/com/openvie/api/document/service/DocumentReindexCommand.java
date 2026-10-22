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

import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.messaging.DocumentIngestRequestedEvent;
import com.openvie.api.document.messaging.DocumentIngestionPublisher;
import com.openvie.api.document.repository.DocumentRepository;
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
