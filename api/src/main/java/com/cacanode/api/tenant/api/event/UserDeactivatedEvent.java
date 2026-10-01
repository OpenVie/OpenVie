package com.cacanode.api.tenant.api.event;

import java.util.UUID;

public record UserDeactivatedEvent(UUID tenantId, UUID userId) {
}
