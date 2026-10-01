package com.cacanode.api.tenant.api.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserInvitedEvent(
        UUID tenantId,
        UUID invitedByUserId,
        String email,
        String tenantName,
        String role,
        String token,
        LocalDateTime expiresAt,
        UUID invitationId,
        String status,
        LocalDateTime createdAt
) {
    public UserInvitedEvent(UUID tenantId, UUID invitedByUserId, String email,
                            String tenantName, String role, String token,
                            LocalDateTime expiresAt) {
        this(tenantId, invitedByUserId, email, tenantName, role, token, expiresAt,
                null, "PENDING", LocalDateTime.now());
    }
}
