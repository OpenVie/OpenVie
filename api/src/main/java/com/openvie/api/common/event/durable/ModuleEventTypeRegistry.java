package com.openvie.api.common.event.durable;

public interface ModuleEventTypeRegistry {
    Class<?> payloadType(String stableType, int version);
}
