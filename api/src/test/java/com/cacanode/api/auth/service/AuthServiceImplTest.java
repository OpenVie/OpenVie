package com.cacanode.api.auth.service;

import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.response.LoginStep1Response;
import com.cacanode.api.auth.api.Login2FAChallengeType;
import com.cacanode.api.auth.model.Login2FAState;
import com.cacanode.api.auth.model.RefreshToken;
import com.cacanode.api.auth.repository.Login2FAStateRepository;
import com.cacanode.api.auth.repository.RefreshTokenRepository;
import com.cacanode.api.auth.service.implement.AuthServiceImpl;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantUserResult;
import com.cacanode.api.tenant.api.UserAuthDto;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private TenantIdentityApi tenants;
    private RefreshTokenRepository refreshTokens;
    private Login2FAStateRepository loginStates;
    private JwtService jwt;
    private Login2FAAttemptService loginAttempts;
    private PasswordEncoder passwordEncoder;
    private ApplicationEventPublisher events;
    private AuthServiceImpl service;

    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantIdentityApi.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        loginStates = mock(Login2FAStateRepository.class);
        jwt = mock(JwtService.class);
        loginAttempts = mock(Login2FAAttemptService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        events = mock(ApplicationEventPublisher.class);
        service = new AuthServiceImpl(
                tenants,
                refreshTokens,
                loginStates,
                jwt,
                loginAttempts,
                passwordEncoder,
                events);

        ReflectionTestUtils.setField(service, "refreshTokenExpiryTime", 30L);
        ReflectionTestUtils.setField(service, "supportEmail", "support@example.com");
        ReflectionTestUtils.setField(service, "verificationResendCooldownSeconds", 60);
        ReflectionTestUtils.setField(service, "maxVerificationResendAttempts", 5);
        ReflectionTestUtils.setField(service, "login2FAExpiryMinutes", 15);
        ReflectionTestUtils.setField(service, "cookieSecure", false);
        ReflectionTestUtils.setField(service, "login2FABypassEmails", "person@example.com");

        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        when(jwt.getAccessTokenExpirySeconds()).thenReturn(900L);
        when(jwt.hashToken(anyString())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
    }

    @Test
    void browserLoginStillCreatesSignedLinkChallengeWithFifteenMinuteExpiry() {
        ReflectionTestUtils.setField(service, "login2FABypassEmails", "");
        arrangeActiveLogin();
        when(loginStates.findByEmail("person@example.com")).thenReturn(Optional.empty());
        when(jwt.generateVerificationToken(userId, "person@example.com")).thenReturn("browser-link-token");
        when(jwt.hashToken("browser-link-token")).thenReturn("browser-link-hash");

        LoginStep1Response response = (LoginStep1Response) service.login(
                browserLogin(), new MockHttpServletResponse());

        assertTrue(response.getMessage().contains("verification link"));
        ArgumentCaptor<Login2FAState> saved = ArgumentCaptor.forClass(Login2FAState.class);
        verify(loginStates).save(saved.capture());
        assertEquals(Login2FAChallengeType.LINK, saved.getValue().getChallengeType());
        assertEquals("browser-link-hash", saved.getValue().getTokenHash());
        assertTrue(saved.getValue().getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(14)));
    }

    @Test
    void bypassedLoginReturnsCredentialsWithoutChallenge() {
        arrangeActiveLogin();
        when(jwt.generateAccessToken(any(), any(), anyString(), anyString())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh-sentinel");
        when(jwt.hashToken("refresh-sentinel")).thenReturn("opaque-hash-value");

        var response = service.login(browserLogin(), new MockHttpServletResponse());

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertEquals("opaque-hash-value", saved.getValue().getTokenHash());
        assertFalse(saved.getValue().getTokenHash().contains("refresh-sentinel"));
        verify(loginStates, never()).save(any());
    }

    @Test
    void browserVerificationLinkStillIssuesCookieCredentials() {
        ReflectionTestUtils.setField(service, "login2FABypassEmails", "");
        Claims claims = mock(Claims.class);
        when(claims.get("userId", String.class)).thenReturn(userId.toString());
        when(claims.getSubject()).thenReturn("person@example.com");
        when(jwt.validateVerificationToken("browser-link-token")).thenReturn(claims);
        when(jwt.hashToken("browser-link-token")).thenReturn("browser-link-hash");
        Login2FAState state = new Login2FAState();
        state.setId(UUID.randomUUID());
        state.setUserId(userId);
        state.setEmail("person@example.com");
        state.setChallengeType(Login2FAChallengeType.LINK);
        state.setTokenHash("browser-link-hash");
        state.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(loginStates.findByEmail("person@example.com")).thenReturn(Optional.of(state));
        when(loginStates.consumeIfActive(any(), any())).thenReturn(1);
        when(tenants.findUserById(userId)).thenReturn(activeUser("ACTIVE"));
        when(jwt.generateAccessToken(any(), any(), anyString(), anyString())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.verifyLogin2FA("browser-link-token", response);

        assertTrue(response.getHeader("Set-Cookie").contains("refresh_token=refresh"));
        verify(loginStates).consumeIfActive(org.mockito.ArgumentMatchers.eq(state.getId()), any());
    }

    @Test
    void inactiveUserCannotCompleteVerification() {
        Claims claims = mock(Claims.class);
        when(claims.get("userId", String.class)).thenReturn(userId.toString());
        when(claims.getSubject()).thenReturn("person@example.com");
        when(jwt.validateVerificationToken("browser-link-token")).thenReturn(claims);
        Login2FAState state = new Login2FAState();
        state.setId(UUID.randomUUID());
        state.setUserId(userId);
        state.setEmail("person@example.com");
        state.setChallengeType(Login2FAChallengeType.LINK);
        state.setTokenHash("hash");
        state.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(loginStates.findByEmail("person@example.com")).thenReturn(Optional.of(state));
        when(loginStates.consumeIfActive(any(), any())).thenReturn(1);
        when(tenants.findUserById(userId)).thenReturn(UserAuthDto.builder()
                .userId(userId)
                .tenantId(tenantId)
                .status("INACTIVE")
                .build());

        assertThrows(
                UnauthorizedException.class,
                () -> service.verifyLogin2FA("browser-link-token", new MockHttpServletResponse()));
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void inactiveUserCannotStartLogin() {
        when(tenants.authenticateUser("person@example.com", "password123"))
                .thenReturn(TenantUserResult.builder()
                        .userId(userId)
                        .tenantId(tenantId)
                        .email("person@example.com")
                        .status("INACTIVE")
                        .build());

        assertThrows(UnauthorizedException.class, () -> service.login(browserLogin(), new MockHttpServletResponse()));
        verify(loginStates, never()).save(any());
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void concurrentLoserCannotIssueReplacement() {
        RefreshToken stored = storedToken(false, false, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:old-refresh")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(any(), anyString(), any())).thenReturn(0);
        when(tenants.findUserById(userId)).thenReturn(activeUser("ACTIVE"));

        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> service.refreshToken("old-refresh", new MockHttpServletResponse()));

        assertEquals("Invalid refresh token", exception.getMessage());
        verify(jwt, never()).generateRefreshToken();
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void browserRefreshRejectsUnknownExpiredAndRevokedTokens() {
        when(refreshTokens.findByTokenHash("hash:unknown")).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("unknown", new MockHttpServletResponse()));

        when(refreshTokens.findByTokenHash("hash:expired"))
                .thenReturn(Optional.of(storedToken(false, false, LocalDateTime.now().minusSeconds(1))));
        assertThrows(UnauthorizedException.class,
                () -> service.refreshToken("expired", new MockHttpServletResponse()));

        when(refreshTokens.findByTokenHash("hash:revoked"))
                .thenReturn(Optional.of(storedToken(true, false, LocalDateTime.now().plusDays(1))));
        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> service.refreshToken("revoked", new MockHttpServletResponse()));
        assertEquals("Refresh token revoked", exception.getMessage());
    }

    @Test
    void browserRefreshRotatesCredentialsIntoPersistentCookie() {
        RefreshToken stored = storedToken(false, true, LocalDateTime.now().plusDays(1));
        when(refreshTokens.findByTokenHash("hash:valid-browser")).thenReturn(Optional.of(stored));
        when(refreshTokens.consumeActiveToken(any(), anyString(), any())).thenReturn(1);
        when(tenants.findUserById(userId)).thenReturn(activeUser("ACTIVE"));
        when(jwt.generateAccessToken(any(), any(), anyString(), anyString())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("rotated");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.refreshToken("valid-browser", response);

        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("refresh_token=rotated"));
        assertTrue(cookie.contains("Path=/api"));
    }

    @Test
    void logoutIsIdempotentAndTargetsOnlySubmittedHash() {
        service.logout("logout-sentinel");

        verify(refreshTokens).revokeByTokenHash("hash:logout-sentinel");
        verify(refreshTokens, never()).revokeAllByUserId(any());
        verify(refreshTokens, never()).findByTokenHash(anyString());
    }

    @Test
    void resendLoginReplacesChallengeAndResetsVerificationAttempts() {
        Login2FAState state = new Login2FAState();
        state.setId(UUID.randomUUID());
        state.setUserId(userId);
        state.setEmail("person@example.com");
        state.setChallengeType(Login2FAChallengeType.LINK);
        state.setTokenHash("old-hash");
        state.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        state.setAttemptCount(1);
        state.setVerificationAttemptCount(3);
        state.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        when(loginStates.findByEmail("person@example.com")).thenReturn(Optional.of(state));
        when(tenants.findUserById(userId)).thenReturn(activeUser("ACTIVE"));
        when(jwt.generateVerificationToken(userId, "person@example.com")).thenReturn("new-link");

        service.resendLogin2FA("person@example.com");

        assertEquals(Login2FAChallengeType.LINK, state.getChallengeType());
        assertEquals(0, state.getVerificationAttemptCount());
        assertTrue(state.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(14)));
        verify(loginStates).save(state);
    }

    @Test
    void resendLoginRejectsAfterAttemptLimit() {
        Login2FAState state = new Login2FAState();
        state.setUserId(userId);
        state.setEmail("person@example.com");
        state.setAttemptCount(5);
        when(loginStates.findByEmail("person@example.com")).thenReturn(Optional.of(state));
        when(tenants.findUserById(userId)).thenReturn(activeUser("ACTIVE"));

        assertThrows(
                UnauthorizedException.class,
                () -> service.resendLogin2FA("person@example.com"));
        verify(refreshTokens).revokeAllByUserId(userId);
    }

    private void arrangeActiveLogin() {
        when(tenants.authenticateUser("person@example.com", "password123"))
                .thenReturn(TenantUserResult.builder()
                        .userId(userId)
                        .tenantId(tenantId)
                        .email("person@example.com")
                        .fullName("Person Name")
                        .role("TENANT_ADMIN")
                        .status("ACTIVE")
                        .build());
        when(tenants.findUserById(userId)).thenReturn(activeUser("ACTIVE"));
    }

    private LoginRequest browserLogin() {
        LoginRequest request = new LoginRequest();
        request.setEmail("person@example.com");
        request.setPassword("password123");
        request.setRememberMe(false);
        return request;
    }

    private UserAuthDto activeUser(String tenantStatus) {
        return UserAuthDto.builder()
                .userId(userId)
                .tenantId(tenantId)
                .email("person@example.com")
                .fullName("Person Name")
                .role("TENANT_ADMIN")
                .status("ACTIVE")
                .tenantStatus(tenantStatus)
                .build();
    }

    private RefreshToken storedToken(boolean revoked, boolean persistent, LocalDateTime expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setUserId(userId);
        token.setTenantId(tenantId);
        token.setTokenHash("stored-hash");
        token.setRevoked(revoked);
        token.setPersistent(persistent);
        token.setExpiresAt(expiresAt);
        return token;
    }
}
