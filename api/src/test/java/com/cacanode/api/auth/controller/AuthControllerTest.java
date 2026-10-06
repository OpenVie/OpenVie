package com.cacanode.api.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.service.AuthService;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.common.exception.custom.UnauthorizedException;

class AuthControllerTest {

    private AuthService authService;
    private TenantIdentityApi identityApi;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        identityApi = mock(TenantIdentityApi.class);
        controller = new AuthController(authService, identityApi);
    }

    @Test
    void switchWorkspaceExtractsAuthenticatedUserWhenTokenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthResponse authResponse = AuthResponse.builder().build();

        when(authService.switchWorkspace(workspaceId, "cookie-token", userId, response))
                .thenReturn(authResponse);

        var result = controller.switchWorkspace(
                Map.of("workspaceId", workspaceId.toString()),
                "cookie-token",
                request,
                response
        );

        assertEquals(authResponse, result.getBody());
        verify(authService).switchWorkspace(workspaceId, "cookie-token", userId, response);
    }

    @Test
    void switchWorkspaceRejectsMissingWorkspaceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(UnauthorizedException.class,
                () -> controller.switchWorkspace(Map.of(), "token", request, response));
    }
}
