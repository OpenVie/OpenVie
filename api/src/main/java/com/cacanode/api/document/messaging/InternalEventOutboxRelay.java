package com.cacanode.api.document.messaging;

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
