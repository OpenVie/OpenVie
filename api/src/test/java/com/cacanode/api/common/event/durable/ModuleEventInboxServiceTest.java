package com.cacanode.api.common.event.durable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleEventInboxServiceTest {
    @AfterEach
    void clearDelivery() {
        ModuleEventDeliveryContext.clear();
    }

    @Test
    void claimsOnceAndSkipsRedeliveryForTheSameConsumer() {
        ModuleEventInboxRepository repository = mock(ModuleEventInboxRepository.class);
        when(repository.claim(eq("analytics.test"), any(), eq("test.event.v1")))
                .thenReturn(1, 0);
        ModuleEventInboxService service = new ModuleEventInboxService(repository);
        ModuleEventDeliveryContext.set(new ModuleEventDeliveryContext.Delivery(
                UUID.randomUUID(), "test.event.v1", 1));

        assertTrue(service.claim("analytics.test"));
        assertFalse(service.claim("analytics.test"));

        verify(repository, org.mockito.Mockito.times(2))
                .claim(eq("analytics.test"), any(), eq("test.event.v1"));
    }
}
