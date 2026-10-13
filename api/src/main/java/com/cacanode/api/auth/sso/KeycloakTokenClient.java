package com.cacanode.api.auth.sso;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.cacanode.api.common.exception.custom.UnauthorizedException;

import lombok.extern.slf4j.Slf4j;

/**
 * PKCE code exchange plus ID-token validation over JWKS.
 *
 * <p>The decoder is built lazily on first exchange (OIDC discovery hits the
 * network), so environments without Keycloak start and run password login
 * untouched. Failures map to 401 with an {@code SSO_*}-prefixed message the
 * frontend contract in docs/KEYCLOAK.md enumerates.
 */
@Service
@Slf4j(topic = "SSO-TOKEN-CLIENT")
public class KeycloakTokenClient implements SsoTokenClient {

    private final SsoProperties properties;
    private final RestClient restClient;

    private volatile JwtDecoder decoder;

    @Autowired
    public KeycloakTokenClient(SsoProperties properties) {
        this(properties, RestClient.create());
    }

    /** Test seam: inject a stubbed HTTP client. */
    public KeycloakTokenClient(SsoProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SsoIdentity exchange(String code, String codeVerifier, String redirectUri) {
        // The code was issued to the public frontend client, so the redemption
        // must present the same client_id with PKCE only (no secret). Mixing
        // in the confidential backend client is rejected by Keycloak with
        // "Found different client_id in clientSession".
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", properties.getFrontendClientId());
        form.add("code_verifier", codeVerifier);
        Map<?, ?> tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri(properties.getIssuerUri() + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError(),
                            (request, response) -> {
                                throw new UnauthorizedException("SSO authorization failed");
                            })
                    .body(Map.class);
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("SSO token endpoint unreachable: {}", e.getMessage());
            throw new UnauthorizedException("SSO provider is unavailable");
        }
        Object idToken = tokenResponse == null ? null : tokenResponse.get("id_token");
        if (!(idToken instanceof String raw) || raw.isBlank()) {
            throw new UnauthorizedException("SSO did not return an ID token");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(raw);
        } catch (Exception e) {
            log.warn("SSO ID token validation failed: {}", e.getMessage());
            throw new UnauthorizedException("SSO token validation failed");
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw new UnauthorizedException("SSO identity is missing email");
        }
        boolean verified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        // Keycloak emits realm_access.roles in the access token but not
        // necessarily in the ID token (mapper default). Both tokens come from
        // the same token response and are validated against the same JWKS, so
        // roles are read from the access token with the ID token as fallback.
        List<String> roles = realmRoles(jwt);
        Object accessToken = tokenResponse.get("access_token");
        if (accessToken instanceof String accessRaw && !accessRaw.isBlank()) {
            try {
                roles = realmRoles(decoder().decode(accessRaw));
            } catch (Exception e) {
                log.warn("SSO access token validation failed: {}", e.getMessage());
                throw new UnauthorizedException("SSO token validation failed");
            }
        }
        List<String> amr = jwt.getClaimAsStringList("amr");
        return new SsoIdentity(subject, email, verified, roles, amr);
    }

    @SuppressWarnings("unchecked")
    private static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> rawRoles) {
            return rawRoles.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private JwtDecoder decoder() {
        JwtDecoder cached = decoder;
        if (cached == null) {
            synchronized (this) {
                cached = decoder;
                if (cached == null) {
                    try {
                        cached = JwtDecoders.fromIssuerLocation(properties.getIssuerUri());
                    } catch (Exception e) {
                        log.warn("SSO issuer discovery failed: {}", e.getMessage());
                        throw new UnauthorizedException("SSO provider is unavailable");
                    }
                    decoder = cached;
                }
            }
        }
        return cached;
    }
}
