package com.cacanode.api.tenant.api.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record TenantCreatedEvent(
        UUID tenantId,
        UUID adminUserId,
        String name,
        String status,
        LocalDateTime createdAt
) {
}
