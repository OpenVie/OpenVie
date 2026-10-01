package com.cacanode.api.tenant.api.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable request to deliver one workspace invitation.
 *
 * <p>Carries both the organization and workspace names so the message reads
 * correctly to the invitee without another lookup.
 */
public record UserInvitedEvent(
        UUID tenantId,
        UUID invitedByUserId,
        String email,
        String organizationName,
        String workspaceName,
        String role,
        String token,
        LocalDateTime expiresAt,
        UUID invitationId,
        String status,
        LocalDateTime createdAt
) {
    public UserInvitedEvent(UUID tenantId, UUID invitedByUserId, String email,
                            String organizationName, String workspaceName, String role,
                            String token, LocalDateTime expiresAt) {
        this(tenantId, invitedByUserId, email, organizationName, workspaceName, role, token,
                expiresAt, null, "PENDING", LocalDateTime.now());
    }
}
