package com.cacanode.api.document.messaging;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RabbitDocumentIngestionPublisher implements DocumentIngestionPublisher {

    private final InternalEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(DocumentIngestRequestedEvent event) {
        InternalEventOutbox outbox = new InternalEventOutbox();
        outbox.setEventId(event.eventId());
        outbox.setAggregateType("document");
        outbox.setAggregateId(event.documentId());
        outbox.setEventType(RabbitMqTopology.INGEST_REQUESTED);
        outbox.setPayload(objectMapper.convertValue(
                event, new com.fasterxml.jackson.core.type.TypeReference<>() { }));
        outboxRepository.save(outbox);
    }
}
