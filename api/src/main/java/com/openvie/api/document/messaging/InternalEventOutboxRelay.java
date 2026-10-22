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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalEventOutboxRelay {
    private final InternalEventOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${app.messaging.outbox-relay-interval-ms:1000}")
    @Transactional
    public void publishDueEvents() {
        for (InternalEventOutbox event : repository.lockDueEvents()) {
            try {
                CorrelationData correlation = new CorrelationData(event.getEventId().toString());
                var message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(event.getPayload()))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setMessageId(event.getEventId().toString())
                        .setCorrelationId(String.valueOf(event.getPayload().get("job_id")))
                        .build();
                rabbitTemplate.setMandatory(true);
                rabbitTemplate.send(
                        RabbitMqTopology.INGESTION_EXCHANGE, event.getEventType(), message, correlation);
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.ack()) {
                    throw new IllegalStateException("RabbitMQ rejected event: " + confirm.reason());
                }
                event.setStatus("PUBLISHED");
                event.setPublishedAt(LocalDateTime.now());
            } catch (Exception exception) {
                event.setAttemptCount(event.getAttemptCount() + 1);
                long delay = Math.min(300, 1L << Math.min(event.getAttemptCount(), 8));
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delay));
                log.warn("Internal outbox publish failed eventId={} attempt={}",
                        event.getEventId(), event.getAttemptCount(), exception);
            }
        }
    }
}
