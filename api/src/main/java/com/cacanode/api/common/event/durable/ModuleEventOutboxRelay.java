package com.cacanode.api.common.event.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModuleEventOutboxRelay {
    private static final int MAX_ATTEMPTS = 10;
    private final ModuleEventOutboxRepository repository;
    private final ModuleEventTypeRegistry registry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Scheduled(fixedDelayString = "${app.module-events.relay-delay-ms:1000}")
    @Transactional
    public void relay() {
        for (ModuleEventOutbox event : repository.lockDueBatch()) {
            deliver(event);
        }
    }

    void deliver(ModuleEventOutbox event) {
        try {
            Class<?> type = registry.payloadType(event.getEventType(), event.getEventVersion());
            Object payload = objectMapper.convertValue(event.getPayload(), type);
            ModuleEventDeliveryContext.set(new ModuleEventDeliveryContext.Delivery(
                    event.getEventId(), event.getEventType(), event.getEventVersion()));
            applicationEventPublisher.publishEvent(payload);
            event.setStatus(ModuleEventStatus.PUBLISHED);
            event.setPublishedAt(LocalDateTime.now());
            event.setLastError(null);
        } catch (Exception exception) {
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            event.setLastError(exception.getMessage());
            if (attempts >= MAX_ATTEMPTS) {
                event.setStatus(ModuleEventStatus.DEAD);
            } else {
                long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
            }
            log.warn("Module event delivery failed eventId={} type={} attempts={}",
                    event.getEventId(), event.getEventType(), attempts, exception);
        } finally {
            ModuleEventDeliveryContext.clear();
        }
    }
}
