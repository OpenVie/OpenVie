package com.cacanode.api.common.event.durable;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModuleEventInboxService {
    private final ModuleEventInboxRepository repository;

    public boolean claim(String consumerName) {
        var delivery = ModuleEventDeliveryContext.currentOrNull();
        if (delivery == null) {
            return true;
        }
        return repository.claim(consumerName, delivery.eventId(), delivery.eventType()) == 1;
    }
}
