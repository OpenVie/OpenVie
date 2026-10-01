package com.cacanode.api.common.event.durable;

import java.util.UUID;

public final class ModuleEventDeliveryContext {
    private static final ThreadLocal<Delivery> CURRENT = new ThreadLocal<>();

    private ModuleEventDeliveryContext() {
    }

    static void set(Delivery delivery) {
        CURRENT.set(delivery);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Delivery current() {
        Delivery delivery = CURRENT.get();
        if (delivery == null) {
            throw new IllegalStateException("No durable module event is being delivered");
        }
        return delivery;
    }

    public static Delivery currentOrNull() {
        return CURRENT.get();
    }

    public record Delivery(UUID eventId, String eventType, int version) {
    }
}
