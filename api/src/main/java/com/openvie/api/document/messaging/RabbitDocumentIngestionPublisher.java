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
