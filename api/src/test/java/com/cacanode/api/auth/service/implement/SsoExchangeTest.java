package com.cacanode.api.auth.service.implement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.cacanode.api.auth.dto.request.SsoExchangeRequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.repository.RefreshTokenRepository;
import com.cacanode.api.auth.service.JwtService;
import com.cacanode.api.auth.sso.SsoIdentity;
import com.cacanode.api.auth.sso.SsoProperties;
import com.cacanode.api.auth.sso.SsoTokenClient;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantCredentials;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantIdentityApi.IdentitySnapshot;
import com.cacanode.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.cacanode.api.tenant.api.TenantIdentityApi.SsoLinkResult;
import com.cacanode.api.tenant.api.TenantIdentityApi.WorkspaceSummary;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;

/**
 * Issue #2: the SSO bridge exchanges a Keycloak code once and then issues the
 * standard internal credential pair, so downstream authorization is identical
 * to a password login. Unknown realm roles are rejected with 403.
 */
class SsoExchangeTest {

    private TenantIdentityApi identity;
    private RefreshTokenRepository refreshTokens;
    private JwtService jwt;
    private TenantCredentials credentials;
    private SsoTokenClient tokenClient;
    private SsoProperties properties;
    private AuthServiceImpl service;

    private UUID userId;
    private UUID orgId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        identity = mock(TenantIdentityApi.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        jwt = mock(JwtService.class);
        credentials = mock(TenantCredentials.class);
        tokenClient = mock(SsoTokenClient.class);
        properties = mock(SsoProperties.class);
        service = new AuthServiceImpl(
                identity, refreshTokens, jwt, credentials, tokenClient, properties);

        ReflectionTestUtils.setField(service, "refreshTokenExpiryTime", 30L);
        ReflectionTestUtils.setField(service, "cookieSecure", false);

        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        when(properties.isEnabled()).thenReturn(true);
        when(jwt.getAccessTokenExpirySeconds()).thenReturn(900L);
        when(jwt.generateAccessToken(any(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh-sentinel");
        when(jwt.hashToken(anyString())).thenAnswer(call -> "hash:" + call.getArgument(0));
    }

    @Test
    void refusedWhenSsoDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        assertThrows(UnauthorizedException.class,
                () -> service.ssoExchange(exchange(), new MockHttpServletResponse()));
        verifyNoInteractions(tokenClient);
    }

    @Test
    void unknownRealmRoleIsRejectedWith403() {
        when(tokenClient.exchange(anyString(), anyString(), anyString())).thenReturn(
                new SsoIdentity("sub-1", "person@example.com", true, List.of("default-roles-openvie"), List.of()));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.ssoExchange(exchange(), new MockHttpServletResponse()));
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertTrue(String.valueOf(exception.getReason()).contains("SSO_ROLE_UNKNOWN"));
    }

    @Test
    void unknownEmailIsRejectedWith404() {
        when(tokenClient.exchange(anyString(), anyString(), anyString())).thenReturn(
                new SsoIdentity("sub-1", "ghost@example.com", true, List.of("MEMBER"), List.of()));
        when(identity.findOrgIdByEmail("ghost@example.com")).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.ssoExchange(exchange(), new MockHttpServletResponse()));
        assertTrue(exception.getMessage().contains("SSO_UNKNOWN_EMAIL"));
    }

    @Test
    void unverifiedEmailPropagatesConflictFromLink() {
        when(tokenClient.exchange(anyString(), anyString(), anyString())).thenReturn(
                new SsoIdentity("sub-1", "person@example.com", false, List.of("MEMBER"), List.of()));
        when(identity.findOrgIdByEmail("person@example.com")).thenReturn(Optional.of(orgId));
        when(identity.linkSsoIdentity(orgId, "sub-1", "person@example.com", false))
                .thenThrow(new ConflictException("SSO requires a verified email address"));

        assertThrows(ConflictException.class,
                () -> service.ssoExchange(exchange(), new MockHttpServletResponse()));
    }

    @Test
    void successIssuesInternalCredentialsLikePasswordLogin() {
        when(tokenClient.exchange(anyString(), anyString(), anyString())).thenReturn(
                new SsoIdentity("sub-1", "person@example.com", true, List.of("MEMBER"), List.of()));
        when(identity.findOrgIdByEmail("person@example.com")).thenReturn(Optional.of(orgId));
        when(identity.linkSsoIdentity(orgId, "sub-1", "person@example.com", true))
                .thenReturn(new SsoLinkResult(userId, true));
        when(identity.findUserById(userId)).thenReturn(new IdentitySnapshot(
                userId, orgId, "person@example.com", "Person", OrgRole.MEMBER,
                UserStatus.ACTIVE.name(), false));
        when(identity.listMemberships(userId)).thenReturn(List.of(
                new WorkspaceSummary(workspaceId, "General", "general", WorkspaceRole.MEMBER, true)));
        when(identity.requireMembership(userId, workspaceId)).thenReturn(new MembershipSnapshot(
                userId, orgId, workspaceId, OrgRole.MEMBER, WorkspaceRole.MEMBER,
                UserStatus.ACTIVE.name()));

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        AuthResponse response = service.ssoExchange(exchange(), servletResponse);

        assertNotNull(response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(workspaceId.toString(), response.getUser().getActiveWorkspaceId());
        assertEquals("MEMBER", response.getUser().getWorkspaceRole());
        assertEquals("MEMBER", response.getUser().getOrgRole());
        assertNotNull(servletResponse.getHeader("Set-Cookie"));
    }

    @Test
    void localRoleWinsOverIdpMismatch() {
        when(tokenClient.exchange(anyString(), anyString(), anyString())).thenReturn(
                new SsoIdentity("sub-1", "person@example.com", true, List.of("ORG_OWNER"), List.of("pwd")));
        when(identity.findOrgIdByEmail("person@example.com")).thenReturn(Optional.of(orgId));
        when(identity.linkSsoIdentity(orgId, "sub-1", "person@example.com", true))
                .thenReturn(new SsoLinkResult(userId, false));
        when(identity.findUserById(userId)).thenReturn(new IdentitySnapshot(
                userId, orgId, "person@example.com", "Person", OrgRole.MEMBER,
                UserStatus.ACTIVE.name(), false));
        when(identity.listMemberships(userId)).thenReturn(List.of(
                new WorkspaceSummary(workspaceId, "General", "general", WorkspaceRole.MEMBER, true)));
        when(identity.requireMembership(userId, workspaceId)).thenReturn(new MembershipSnapshot(
                userId, orgId, workspaceId, OrgRole.MEMBER, WorkspaceRole.MEMBER,
                UserStatus.ACTIVE.name()));

        AuthResponse response = service.ssoExchange(exchange(), new MockHttpServletResponse());

        assertEquals("MEMBER", response.getUser().getOrgRole());
        assertEquals("MEMBER", response.getUser().getWorkspaceRole());
    }

    private SsoExchangeRequest exchange() {
        SsoExchangeRequest request = new SsoExchangeRequest();
        request.setCode("auth-code");
        request.setCodeVerifier("verifier");
        request.setRedirectUri("http://localhost:3000/api/auth/callback");
        return request;
    }
}
