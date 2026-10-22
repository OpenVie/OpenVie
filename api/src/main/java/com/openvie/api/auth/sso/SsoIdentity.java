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

package com.openvie.api.auth.sso;

import java.util.List;

/**
 * Verified Keycloak identity claims needed by the SSO bridge. Identity comes
 * from the ID token; realm roles come from the access token of the same token
 * response (Keycloak does not necessarily copy them into the ID token). The
 * Keycloak tokens themselves are discarded afterwards because API
 * authorization always runs on internal workspace-scoped credentials.
 *
 * <p>{@code amr} is preserved (logged at exchange) as the seam for the future
 * TOTP-confirmation branch: callers holding a Keycloak-OTP-authenticated
 * session carry {@code "otp"} here. No SMTP code is involved.
 */
public record SsoIdentity(
        String subject,
        String email,
        boolean emailVerified,
        List<String> realmRoles,
        List<String> amr) {

    public SsoIdentity {
        realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
        amr = amr == null ? List.of() : List.copyOf(amr);
    }
}
