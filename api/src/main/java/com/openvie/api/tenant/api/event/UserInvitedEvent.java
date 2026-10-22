// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.tenant.api.event;

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
