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

package com.openvie.api.document.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.openvie.api.document.service.DocumentService;
import com.openvie.api.integration.webhook.WebhookEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class DocumentStatusEventListener {

    private final ObjectMapper objectMapper;
    private final DocumentService documentService;
    private final InternalEventInboxRepository inboxRepository;
    private final WebhookEventPublisher webhookEventPublisher;

    @Autowired
    public DocumentStatusEventListener(
            ObjectMapper objectMapper,
            DocumentService documentService,
            InternalEventInboxRepository inboxRepository,
            WebhookEventPublisher webhookEventPublisher
    ) {
        this.objectMapper = objectMapper;
        this.documentService = documentService;
        this.inboxRepository = inboxRepository;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    public DocumentStatusEventListener(ObjectMapper objectMapper, DocumentService documentService) {
        this(objectMapper, documentService, null, null);
    }

    @RabbitListener(queues = RabbitMqTopology.STATUS_QUEUE)
    @Transactional
    public void onMessage(Message message) {
        DocumentStatusEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), DocumentStatusEvent.class);
        } catch (Exception e) {
            log.warn("Ignoring malformed document status event: {}", e.getMessage());
            return;
        }
        if (event.eventId() == null || event.tenantId() == null
                || event.documentId() == null || event.status() == null) {
            log.warn("Ignoring malformed document status event");
            return;
        }
        if (inboxRepository != null && inboxRepository.existsById(event.eventId())) {
            return;
        }
        InternalEventInbox inbox = new InternalEventInbox();
        inbox.setEventId(event.eventId());
        inbox.setEventType("document.ingest." + event.status().toLowerCase());
        inbox.setAggregateId(event.documentId());
        inbox.setPayload(objectMapper.convertValue(
                event, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        if (inboxRepository != null) {
            inboxRepository.save(inbox);
        }

        boolean updated = documentService.applyStatusEvent(event);
        inbox.setProcessedAt(LocalDateTime.now());
        inbox.setProcessingResult(updated ? "APPLIED" : "IGNORED");
        if (!updated) {
            log.warn(
                    "Ignoring document status event for unknown document: tenantId={}, documentId={}",
                    event.tenantId(),
                    event.documentId()
            );
        }

        // Integration surface: ingestion progress is a headless-platform event,
        // journaled into the webhook outbox in this same transaction. The
        // eventId doubles as the receiver-side dedupe key (plus the inbox id
        // guard above, which already replays nothing).
        publishWebhook(event);
    }

    private void publishWebhook(DocumentStatusEvent event) {
        if (webhookEventPublisher == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("document_id", event.documentId().toString());
            data.put("job_id", event.jobId() == null ? null : event.jobId().toString());
            data.put("status", event.status());
            data.put("chunk_count", event.chunkCount());
            data.put("text_source", event.textSource());
            data.put("page_count", event.pageCount());
            data.put("error_message", event.errorMessage());
            data.put("schema_version", event.schemaVersion());
            webhookEventPublisher.publish(
                    event.tenantId(), "document.status.changed", event.eventId(), data);
        } catch (Exception exception) {
            // Webhook journaling must never fail the ingestion callback path.
            log.warn("Webhook outbox write failed for document status event: {}",
                    exception.getMessage());
        }
    }
}
