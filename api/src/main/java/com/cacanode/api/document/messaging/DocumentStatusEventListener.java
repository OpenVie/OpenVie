package com.cacanode.api.document.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.cacanode.api.document.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@Slf4j
public class DocumentStatusEventListener {

    private final ObjectMapper objectMapper;
    private final DocumentService documentService;
    private final InternalEventInboxRepository inboxRepository;

    @Autowired
    public DocumentStatusEventListener(
            ObjectMapper objectMapper,
            DocumentService documentService,
            InternalEventInboxRepository inboxRepository
    ) {
        this.objectMapper = objectMapper;
        this.documentService = documentService;
        this.inboxRepository = inboxRepository;
    }

    public DocumentStatusEventListener(ObjectMapper objectMapper, DocumentService documentService) {
        this(objectMapper, documentService, null);
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
    }
}
