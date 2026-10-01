package com.cacanode.api.common.service;

import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.model.AuditLog;
import com.cacanode.api.common.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SynchronousAuditRecorder {
    private final AuditLogRepository repository;

    public void record(UUID tenantId, UUID actorId, LogAction action, String resourceType,
                       UUID resourceId, String ipAddress, String userAgent,
                       Map<String, Object> metadata) {
        AuditLog value = new AuditLog();
        value.setTenantId(tenantId);
        value.setUserId(actorId);
        value.setAction(action);
        value.setResourceType(resourceType);
        value.setResourceId(resourceId);
        value.setIpAddress(ipAddress);
        value.setUserAgent(userAgent);
        value.setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata));
        repository.save(value);
    }
}
