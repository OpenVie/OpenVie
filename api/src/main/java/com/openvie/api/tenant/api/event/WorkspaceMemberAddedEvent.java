package com.openvie.api.tenant.api.event;

import java.util.UUID;

/**
 * Notice that an administrator added an existing account to a workspace.
 *
 * <p>Best-effort: delivery is attempted after the membership transaction
 * commits and a missing channel or transport failure never fails the
 * membership itself. Carries both the organization and workspace names, so
 * the message reads correctly to the added member.
 */
public record WorkspaceMemberAddedEvent(
        UUID orgId,
        UUID workspaceId,
        UUID userId,
        String email,
        String fullName,
        String organizationName,
        String workspaceName,
        String role
) {
}
