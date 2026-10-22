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
