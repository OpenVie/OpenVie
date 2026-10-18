package com.openvie.api.auth.service.implement;

import com.openvie.api.auth.dto.request.AcceptInvitationRequest;
import com.openvie.api.auth.dto.request.LoginRequest;
import com.openvie.api.auth.dto.request.RegisterRequest;
import com.openvie.api.auth.dto.request.SsoExchangeRequest;
import com.openvie.api.auth.dto.response.AuthResponse;
import com.openvie.api.auth.dto.response.InvitationValidationResponse;
import com.openvie.api.auth.dto.response.WorkspaceSummaryResponse;
import com.openvie.api.auth.model.RefreshToken;
import com.openvie.api.auth.repository.RefreshTokenRepository;
import com.openvie.api.auth.service.AuthService;
import com.openvie.api.auth.service.JwtService;
import com.openvie.api.auth.sso.SsoIdentity;
import com.openvie.api.auth.sso.SsoProperties;
import com.openvie.api.auth.sso.SsoTokenClient;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.exception.custom.UnauthorizedException;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantIdentityApi.AcceptedAccount;
import com.openvie.api.tenant.api.TenantIdentityApi.AuthenticatedIdentity;
import com.openvie.api.tenant.api.TenantIdentityApi.IdentitySnapshot;
import com.openvie.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.openvie.api.tenant.api.TenantIdentityApi.SsoLinkResult;
import com.openvie.api.tenant.api.TenantIdentityApi.WorkspaceSummary;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.api.TenantCredentials;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j(topic = "AUTH-SERVICE")
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    @Value("${jwt.expiry-days}")
    private Long refreshTokenExpiryTime;

    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${app.security.cookie-same-site:Lax}")
    private String cookieSameSite = "Lax";

    private final TenantIdentityApi identityApi;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final TenantCredentials credentialService;
    private final SsoTokenClient ssoTokenClient;
    private final SsoProperties ssoProperties;

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        AuthenticatedIdentity authenticated =
                identityApi.authenticate(request.getEmail(), request.getPassword());
        if (authenticated == null) {
            throw new ConflictException("Invalid email or password");
        }
        CredentialPair credentials = issueCredentials(
                authenticated.identity(), authenticated.defaultWorkspaceId(),
                authenticated.defaultWorkspaceRole(), request.isRememberMe());
        return deliver(credentials, response);
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(String refreshToken, HttpServletResponse response) {
        return deliver(rotate(refreshToken), response);
    }

    /**
     * SSO bridge. The Keycloak code is exchanged server-side, the ID
     * token is validated over JWKS, and the pre-SSO account is linked by
     * verified email. What leaves this method is the standard
     * internal credential pair, so workspace authorization downstream is
     * identical to a password login. Local membership stays the authority:
     * IdP/local role mismatches are logged, never applied.
     */
    @Override
    @Transactional
    public AuthResponse ssoExchange(SsoExchangeRequest request, HttpServletResponse response) {
        if (!ssoProperties.isEnabled()) {
            throw new UnauthorizedException("SSO is not enabled");
        }
        SsoIdentity sso = ssoTokenClient.exchange(
                request.getCode(), request.getCodeVerifier(), request.getRedirectUri());
        requireKnownRealmRole(sso.realmRoles());
        UUID orgId = identityApi.findOrgIdByEmail(sso.email())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SSO_UNKNOWN_EMAIL: No account exists for this email"));
        SsoLinkResult link = identityApi.linkSsoIdentity(
                orgId, sso.subject(), sso.email(), sso.emailVerified());
        IdentitySnapshot identity = identityApi.findUserById(link.userId());
        if (identity == null) {
            throw new UnauthorizedException("Session is no longer valid");
        }
        if (!identity.orgRole().name().equals("ORG_OWNER")
                && sso.realmRoles().contains("ORG_OWNER")) {
            log.warn("SSO IdP/local org-role mismatch userId={} (IdP ORG_OWNER, local {}): local wins",
                    link.userId(), identity.orgRole());
        }
        UUID workspaceId = defaultWorkspaceFor(link.userId());
        MembershipSnapshot membership = identityApi.requireMembership(link.userId(), workspaceId);
        log.info("sso_exchange userId={} newlyLinked={} amr={}",
                link.userId(), link.newlyLinked(), sso.amr());
        CredentialPair credentials = issueCredentials(
                identity, workspaceId, membership.workspaceRole(), true);
        return deliver(credentials, response);
    }

    /** Realm roles the bridge understands; anything else is rejected with 403. */
    private static void requireKnownRealmRole(List<String> realmRoles) {
        if (realmRoles.contains("ORG_OWNER")
                || realmRoles.contains("WORKSPACE_ADMIN")
                || realmRoles.contains("MEMBER")) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "SSO_ROLE_UNKNOWN: No recognized realm role");
    }

    private UUID defaultWorkspaceFor(UUID userId) {
        List<WorkspaceSummary> memberships = identityApi.listMemberships(userId);
        return memberships.stream()
                .filter(WorkspaceSummary::isDefault)
                .findFirst()
                .or(() -> memberships.stream().findFirst())
                .map(WorkspaceSummary::workspaceId)
                .orElseThrow(() -> new UnauthorizedException("You are not a member of any workspace"));
    }

    @Override
    @Transactional
    public AuthResponse switchWorkspace(
            UUID workspaceId, String refreshToken, HttpServletResponse response) {
        return switchWorkspace(workspaceId, refreshToken, null, response);
    }

    @Override
    @Transactional
    public AuthResponse switchWorkspace(
            UUID workspaceId, String refreshToken, UUID authenticatedUserId, HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                StoredCredential stored = consumeRefresh(refreshToken);
                MembershipSnapshot membership =
                        identityApi.requireMembership(stored.identity().userId(), workspaceId);
                CredentialPair credentials = issueCredentials(
                        stored.identity(), workspaceId, membership.workspaceRole(), stored.persistent());
                return deliver(credentials, response);
            } catch (UnauthorizedException e) {
                if (authenticatedUserId == null) {
                    throw e;
                }
            }
        }
        if (authenticatedUserId == null) {
            throw new UnauthorizedException("Refresh token missing");
        }
        IdentitySnapshot identity = identityApi.findUserById(authenticatedUserId);
        if (identity == null) {
            throw new UnauthorizedException("Session is no longer valid");
        }
        MembershipSnapshot membership =
                identityApi.requireMembership(authenticatedUserId, workspaceId);
        CredentialPair credentials = issueCredentials(
                identity, workspaceId, membership.workspaceRole(), true);
        return deliver(credentials, response);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepository.revokeByTokenHash(jwtService.hashToken(refreshToken));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceSummaryResponse> listWorkspaces(UUID userId) {
        return identityApi.listMemberships(userId).stream()
                .map(summary -> WorkspaceSummaryResponse.builder()
                        .id(summary.workspaceId())
                        .name(summary.name())
                        .slug(summary.slug())
                        .role(summary.role().name())
                        .isDefault(summary.isDefault())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        IdentitySnapshot identity = identityApi.findUserById(userId);
        if (identity == null) {
            throw new UnauthorizedException("Session is no longer valid");
        }
        credentialService.changePassword(userId, currentPassword, newPassword);
    }

    private CredentialPair rotate(String refreshToken) {
        StoredCredential stored = consumeRefresh(refreshToken);
        MembershipSnapshot membership = identityApi.requireMembership(
                stored.identity().userId(), stored.workspaceId());
        return issueCredentials(stored.identity(), stored.workspaceId(),
                membership.workspaceRole(), stored.persistent());
    }

    private StoredCredential consumeRefresh(String refreshToken) {
        String tokenHash = jwtService.hashToken(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (stored.isRevoked()) {
            throw new UnauthorizedException("Refresh token revoked");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!stored.getExpiresAt().isAfter(now)) {
            throw new UnauthorizedException("Refresh token expired");
        }
        IdentitySnapshot identity = identityApi.findUserById(stored.getUserId());
        if (identity == null) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        int consumed = refreshTokenRepository.consumeActiveToken(stored.getId(), tokenHash, now);
        if (consumed != 1) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        return new StoredCredential(identity, stored.getTenantId(), stored.isPersistent());
    }

    private CredentialPair issueCredentials(
            IdentitySnapshot identity, UUID workspaceId, WorkspaceRole workspaceRole, boolean persistent) {
        String accessToken = jwtService.generateAccessToken(
                identity.userId(),
                identity.orgId(),
                workspaceId,
                identity.email(),
                identity.orgRole().name(),
                workspaceRole.name());
        String refreshTokenValue = jwtService.generateRefreshToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(identity.userId());
        refreshToken.setTenantId(workspaceId);
        refreshToken.setTokenHash(jwtService.hashToken(refreshTokenValue));
        refreshToken.setExpiresAt(LocalDateTime.now().plusDays(refreshTokenExpiryTime));
        refreshToken.setRevoked(false);
        refreshToken.setPersistent(persistent);
        refreshTokenRepository.save(refreshToken);

        return new CredentialPair(
                accessToken,
                refreshTokenValue,
                jwtService.getAccessTokenExpirySeconds(),
                persistent,
                AuthResponse.UserInfo.builder()
                        .userId(identity.userId().toString())
                        .orgId(identity.orgId().toString())
                        .activeWorkspaceId(workspaceId.toString())
                        .email(identity.email())
                        .fullName(identity.fullName())
                        .orgRole(identity.orgRole().name())
                        .workspaceRole(workspaceRole.name())
                        .mustChangePassword(identity.mustChangePassword())
                        .build());
    }

    private AuthResponse deliver(CredentialPair credentials, HttpServletResponse response) {
        Duration maxAge = credentials.persistent()
                ? Duration.ofDays(refreshTokenExpiryTime)
                : Duration.ofSeconds(-1);
        ResponseCookie cookie = ResponseCookie.from("refresh_token", credentials.refreshToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api")
                .maxAge(maxAge)
                .sameSite(cookieSameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return AuthResponse.builder()
                .accessToken(credentials.accessToken())
                .tokenType("Bearer")
                .expiresIn(credentials.expiresIn())
                .user(credentials.user())
                .build();
    }

    @Override
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request, HttpServletResponse response) {
        String passwordHash = credentialService.hashNewPassword(request.getPassword());
        AcceptedAccount account = identityApi.register(
                request.getEmail(), request.getFullName(), passwordHash);
        return deliver(credentialsFor(account), response);
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationValidationResponse validateInvitation(String token) {
        var invitation = identityApi.validateInvitation(token);
        return new InvitationValidationResponse(
                invitation.email(), invitation.organizationName(), invitation.workspaceName(),
                invitation.role().name(), invitation.expiresAt());
    }

    @Override
    @Transactional
    public AuthResponse acceptInvitation(AcceptInvitationRequest request, HttpServletResponse response) {
        String passwordHash = credentialService.hashNewPassword(request.getPassword());
        AcceptedAccount account = identityApi.acceptInvitation(
                request.getToken(), request.getFullName(), passwordHash);
        return deliver(credentialsFor(account), response);
    }

    /** Issues a credential pair for an account that was just created. */
    private CredentialPair credentialsFor(AcceptedAccount account) {
        IdentitySnapshot identity = new IdentitySnapshot(
                account.userId(), account.orgId(), account.email(), account.fullName(),
                account.orgRole(), account.status(), account.mustChangePassword());
        return issueCredentials(identity, account.workspaceId(), account.workspaceRole(), true);
    }
    private record CredentialPair(
            String accessToken,
            String refreshToken,
            long expiresIn,
            boolean persistent,
            AuthResponse.UserInfo user) {
    }

    private record StoredCredential(IdentitySnapshot identity, UUID workspaceId, boolean persistent) {
    }
}
