package com.cacanode.api.auth.sso;

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
