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

package com.openvie.api.auth.controller;

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

import com.openvie.api.auth.dto.response.AuthResponse;
import com.openvie.api.auth.service.AuthService;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.common.exception.custom.UnauthorizedException;

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
