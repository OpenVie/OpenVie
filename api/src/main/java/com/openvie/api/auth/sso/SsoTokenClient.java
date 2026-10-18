package com.openvie.api.auth.sso;

/**
 * Server-side PKCE code exchange against the Keycloak token endpoint.
 * Separated from the orchestration so unit tests can stub the IdP round-trip.
 */
public interface SsoTokenClient {

    /**
     * Exchanges the authorization code and validates the returned ID token
     * (issuer, expiry, JWKS signature).
     *
     * @throws com.openvie.api.common.exception.custom.UnauthorizedException
     *             when the provider is unreachable or the token is invalid
     */
    SsoIdentity exchange(String code, String codeVerifier, String redirectUri);
}
