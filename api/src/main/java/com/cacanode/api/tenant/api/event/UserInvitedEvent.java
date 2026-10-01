package com.cacanode.api.tenant.api.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable request to deliver one workspace invitation.
 *
 * <p>Carries the organization id so the outbox relay can decide whether the
 * organization currently has an enabled delivery channel without a database
 * lookup, and both the organization and workspace names so the message reads
 * correctly to the invitee.
 */
public record UserInvitedEvent(
        UUID orgId,
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
    public UserInvitedEvent(UUID orgId, UUID tenantId, UUID invitedByUserId, String email,
                            String organizationName, String workspaceName, String role,
                            String token, LocalDateTime expiresAt) {
        this(orgId, tenantId, invitedByUserId, email, organizationName, workspaceName, role, token,
                expiresAt, null, "PENDING", LocalDateTime.now());
    }
}
