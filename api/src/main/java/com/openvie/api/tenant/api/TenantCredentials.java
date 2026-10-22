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

package com.openvie.api.tenant.api;

import java.util.UUID;

/**
 * Password rules and credential writes, published so other modules never touch
 * the users table or re-implement the strength policy.
 *
 * <p>Setup, self-registration, invitation acceptance, administrator-set initial
 * passwords, and self-service changes all pass through this one gate.
 */
public interface TenantCredentials {

    int MIN_PASSWORD_LENGTH = 12;

    /**
     * Validates strength and returns the digest to persist.
     *
     * @throws com.openvie.api.common.exception.custom.BadRequestException when
     *             the password is short, blank, or the published dev password
     */
    String hashNewPassword(String password);

    /** Self-service change; clears any forced-change flag. */
    void changePassword(UUID userId, String currentPassword, String newPassword);

    /**
     * Administrator reset: no current password required, and the account must
     * change it on next login.
     */
    void setInitialPassword(UUID actorId, UUID userId, String newPassword);
}
