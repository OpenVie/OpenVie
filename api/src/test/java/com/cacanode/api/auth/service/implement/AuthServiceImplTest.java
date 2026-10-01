package com.cacanode.api.auth.service.implement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.cacanode.api.auth.dto.request.AcceptInvitationRequest;
import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.request.RegisterRequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.model.RefreshToken;
import com.cacanode.api.auth.repository.RefreshTokenRepository;
import com.cacanode.api.auth.service.JwtService;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantIdentityApi.AcceptedAccount;
import com.cacanode.api.tenant.api.TenantIdentityApi.AuthenticatedIdentity;
import com.cacanode.api.tenant.api.TenantIdentityApi.IdentitySnapshot;
import com.cacanode.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.cacanode.api.tenant.api.TenantIdentityApi.WorkspaceSummary;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.api.TenantCredentials;

/**
 * Login is password-only: no email, no 2FA challenge, no verification state.
 * Tokens carry an active workspace and are reissued on switch.
 */
class AuthServiceImplTest {

    private TenantIdentityApi identity;
    private RefreshTokenRepository refreshTokens;
    private JwtService jwt;
    private TenantCredentials credentials;
    private AuthServiceImpl service;

    private UUID userId;
    private UUID orgId;
    private UUID workspaceA;
    private UUID workspaceB;

    @BeforeEach
    void setUp() {
        identity = mock(TenantIdentityApi.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        jwt = mock(JwtService.class);
        credentials = mock(TenantCredentials.class);
        service = new AuthServiceImpl(identity, refreshTokens, jwt, credentials);

        ReflectionTestUtils.setField(service, "refreshTokenExpiryTime", 30L);
        ReflectionTestUtils.setField(service, "cookieSecure", false);

        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        workspaceA = UUID.randomUUID();
        workspaceB = UUID.randomUUID();

        when(jwt.getAccessTokenExpirySeconds()).thenReturn(900L);
        when(jwt.generateAccessToken(any(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh-sentinel");
        when(jwt.hashToken(anyString())).thenAnswer(call -> "hash:" + call.getArgument(0));
    }

    @Test
    void loginReturnsCredentialsDirectlyWithNoEmailChallenge() {
        when(identity.authenticate("person@example.com", "correct-horse"))
                .thenReturn(authenticated(workspaceA, WorkspaceRole.WORKSPACE_ADMIN));
        // Production hashToken is SHA-256; the generic stub echoes its input,
        // so pin an opaque digest here to assert the raw token is not stored.
        when(jwt.hashToken("refresh-sentinel")).thenReturn("opaque-refresh-hash");

        AuthResponse response = service.login(login("correct-horse", false), new MockHttpServletResponse());

        assertNotNull(response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(workspaceA.toString(), response.getUser().getActiveWorkspaceId());
        assertEquals("WORKSPACE_ADMIN", response.getUser().getWorkspaceRole());
        assertEquals("ORG_OWNER", response.getUser().getOrgRole());

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertEquals(workspaceA, saved.getValue().getTenantId());
        // Only the hash is persisted, never the presented token.
        assertFalse(saved.getValue().getTokenHash().contains("refresh-sentinel"));
    }

    @Test
    void loginWritesRefreshCookieScopedToApiPath() {
        when(identity.authenticate(any(), any()))
                .thenReturn(authenticated(workspaceA, WorkspaceRole.MEMBER));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.login(login("correct-horse", true), response);

        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("refresh_token=refresh-sentinel"));
        assertTrue(cookie.contains("Path=/api"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Max-Age=2592000"), "rememberMe persists for 30 days");
    }

    @Test
    void wrongPasswordIsRejectedWithoutIssuingCredentials() {
        when(identity.authenticate("person@example.com", "wrong")).thenReturn(null);

        assertThrows(ConflictException.class,
                () -> service.login(login("wrong", false), new MockHttpServletResponse()));
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void suspendedAccountCannotLogIn() {
        when(identity.authenticate("person@example.com", "correct-horse"))
                .thenThrow(new UnauthorizedException("Account suspended."));

        assertThrows(UnauthorizedException.class,
                () -> service.login(login("correct-horse", false), new MockHttpServletResponse()));
    }

    @Test
    void refreshKeepsTheActiveWorkspaceAndRotatesTheToken() {
        RefreshToken stored = stored(workspaceA, false, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:old-refresh")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(eq(stored.getId()), eq("hash:old-refresh"), any()))
                .thenReturn(1);
        when(identity.findUserById(userId)).thenReturn(identitySnapshot());
        when(identity.requireMembership(userId, workspaceA))
                .thenReturn(membership(workspaceA, WorkspaceRole.WORKSPACE_ADMIN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthResponse result = service.refreshToken("old-refresh", response);

        assertEquals(workspaceA.toString(), result.getUser().getActiveWorkspaceId());
        assertTrue(response.getHeader("Set-Cookie").contains("refresh_token=refresh-sentinel"));
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertEquals(workspaceA, saved.getValue().getTenantId());
    }

    @Test
    void refreshRejectsUnknownExpiredAndRevokedTokens() {
        when(refreshTokens.findByTokenHash("hash:unknown")).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("unknown", new MockHttpServletResponse()));

        when(refreshTokens.findByTokenHash("hash:expired")).thenReturn(Optional.of(
                stored(workspaceA, false, LocalDateTime.now().minusSeconds(1))));
        assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("expired", new MockHttpServletResponse()));

        when(refreshTokens.findByTokenHash("hash:revoked")).thenReturn(Optional.of(
                stored(workspaceA, true, LocalDateTime.now().plusDays(1))));
        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("revoked", new MockHttpServletResponse()));
        assertEquals("Refresh token revoked", exception.getMessage());
    }

    @Test
    void refreshRefusesAWorkspaceTheUserNoLongerBelongsTo() {
        RefreshToken stored = stored(workspaceA, false, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:old")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(any(), any(), any())).thenReturn(1);
        when(identity.findUserById(userId)).thenReturn(identitySnapshot());
        when(identity.requireMembership(userId, workspaceA))
                .thenThrow(new UnauthorizedException("You are not a member of this workspace"));

        assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("old", new MockHttpServletResponse()));
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void concurrentRefreshLoserCannotIssueReplacementCredentials() {
        RefreshToken stored = stored(workspaceA, false, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:old")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(any(), any(), any())).thenReturn(0);
        when(identity.findUserById(userId)).thenReturn(identitySnapshot());

        assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("old", new MockHttpServletResponse()));
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void switchWorkspaceReissuesForTheTargetWorkspace() {
        RefreshToken stored = stored(workspaceA, false, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:old")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(any(), any(), any())).thenReturn(1);
        when(identity.findUserById(userId)).thenReturn(identitySnapshot());
        when(identity.requireMembership(userId, workspaceB))
                .thenReturn(membership(workspaceB, WorkspaceRole.MEMBER));

        AuthResponse result = service.switchWorkspace(
                workspaceB, "old", new MockHttpServletResponse());

        assertEquals(workspaceB.toString(), result.getUser().getActiveWorkspaceId());
        assertEquals("MEMBER", result.getUser().getWorkspaceRole());
    }

    @Test
    void switchWorkspaceRefusesAWorkspaceTheUserIsNotAMemberOf() {
        RefreshToken stored = stored(workspaceA, false, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:old")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(any(), any(), any())).thenReturn(1);
        when(identity.findUserById(userId)).thenReturn(identitySnapshot());
        when(identity.requireMembership(userId, workspaceB))
                .thenThrow(new UnauthorizedException("You are not a member of this workspace"));

        assertThrows(UnauthorizedException.class,
                () -> service.switchWorkspace(workspaceB, "old", new MockHttpServletResponse()));
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void logoutRevokesOnlyThePresentedTokenHash() {
        service.logout("logout-sentinel");

        verify(refreshTokens).revokeByTokenHash("hash:logout-sentinel");
        verify(refreshTokens, never()).revokeAllByUserId(any());
    }

    @Test
    void logoutWithNoTokenIsANoOp() {
        service.logout(null);
        service.logout("  ");

        verify(refreshTokens, never()).revokeByTokenHash(anyString());
    }

    @Test
    void listWorkspacesReturnsEveryMembership() {
        when(identity.listMemberships(userId)).thenReturn(List.of(
                new WorkspaceSummary(workspaceA, "General", "general", WorkspaceRole.WORKSPACE_ADMIN, true),
                new WorkspaceSummary(workspaceB, "Finance", "finance", WorkspaceRole.MEMBER, false)));

        var result = service.listWorkspaces(userId);

        assertEquals(2, result.size());
        assertEquals("General", result.get(0).getName());
        assertTrue(result.get(0).isDefault());
        assertEquals("MEMBER", result.get(1).getRole());
    }

    @Test
    void registerIssuesCredentialsForTheNewAccount() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setFullName("New Person");
        request.setPassword("a-fresh-long-password");
        when(credentials.hashNewPassword("a-fresh-long-password")).thenReturn("encoded");
        when(identity.register("new@example.com", "New Person", "encoded"))
                .thenReturn(accepted(workspaceA));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthResponse result = service.register(request, response);

        assertEquals("new@example.com", result.getUser().getEmail());
        assertEquals(workspaceA.toString(), result.getUser().getActiveWorkspaceId());
        assertTrue(response.getHeader("Set-Cookie").contains("refresh_token=refresh-sentinel"));
    }

    @Test
    void acceptInvitationIssuesCredentialsForTheInvitedWorkspace() {
        AcceptInvitationRequest request = new AcceptInvitationRequest();
        request.setToken("invite-token");
        request.setFullName("New Person");
        request.setPassword("a-fresh-long-password");
        when(credentials.hashNewPassword("a-fresh-long-password")).thenReturn("encoded");
        when(identity.acceptInvitation("invite-token", "New Person", "encoded"))
                .thenReturn(accepted(workspaceB));

        AuthResponse result = service.acceptInvitation(request, new MockHttpServletResponse());

        assertEquals(workspaceB.toString(), result.getUser().getActiveWorkspaceId());
    }

    @Test
    void changePasswordDelegatesToTheCredentialGate() {
        when(identity.findUserById(userId)).thenReturn(identitySnapshot());

        service.changePassword(userId, "current-password", "a-fresh-long-password");

        verify(credentials).changePassword(userId, "current-password", "a-fresh-long-password");
    }

    @Test
    void changePasswordRefusesAnUnknownSession() {
        when(identity.findUserById(userId)).thenReturn(null);

        assertThrows(UnauthorizedException.class,
                () -> service.changePassword(userId, "current", "a-fresh-long-password"));
        verify(credentials, never()).changePassword(any(), any(), any());
    }

    @Test
    void noTwoFactorSurfaceRemainsOnTheService() {
        // Email is optional and login is password-only: the 2FA contract is gone.
        assertTrue(java.util.Arrays.stream(service.getClass().getDeclaredMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("2fa")
                        || method.getName().toLowerCase().contains("verifylogin")
                        || method.getName().toLowerCase().contains("resend")));
    }

    private LoginRequest login(String password, boolean rememberMe) {
        LoginRequest request = new LoginRequest();
        request.setEmail("person@example.com");
        request.setPassword(password);
        request.setRememberMe(rememberMe);
        return request;
    }

    private AuthenticatedIdentity authenticated(UUID workspaceId, WorkspaceRole role) {
        return new AuthenticatedIdentity(identitySnapshot(), workspaceId, role);
    }

    private IdentitySnapshot identitySnapshot() {
        return new IdentitySnapshot(userId, orgId, "person@example.com", "Person",
                OrgRole.ORG_OWNER, UserStatus.ACTIVE.name(), false);
    }

    private MembershipSnapshot membership(UUID workspaceId, WorkspaceRole role) {
        return new MembershipSnapshot(userId, orgId, workspaceId, OrgRole.ORG_OWNER,
                role, UserStatus.ACTIVE.name());
    }

    private AcceptedAccount accepted(UUID workspaceId) {
        return new AcceptedAccount(userId, orgId, workspaceId, "new@example.com", "New Person",
                OrgRole.MEMBER, WorkspaceRole.MEMBER, UserStatus.ACTIVE.name(), false);
    }

    private RefreshToken stored(UUID workspaceId, boolean revoked, LocalDateTime expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setUserId(userId);
        token.setTenantId(workspaceId);
        token.setTokenHash("hash");
        token.setExpiresAt(expiresAt);
        token.setRevoked(revoked);
        token.setPersistent(true);
        token.setCreatedAt(LocalDateTime.now());
        return token;
    }
}
