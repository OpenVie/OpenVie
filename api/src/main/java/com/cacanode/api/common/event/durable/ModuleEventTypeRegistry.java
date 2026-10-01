package com.cacanode.api.common.event.durable;

public interface ModuleEventTypeRegistry {
    Class<?> payloadType(String stableType, int version);
}
