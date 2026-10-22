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

package com.openvie.api.auth.service;

import com.openvie.api.auth.dto.request.AcceptInvitationRequest;
import com.openvie.api.auth.dto.request.LoginRequest;
import com.openvie.api.auth.dto.request.RegisterRequest;
import com.openvie.api.auth.dto.request.SsoExchangeRequest;
import com.openvie.api.auth.dto.response.AuthResponse;
import com.openvie.api.auth.dto.response.InvitationValidationResponse;
import com.openvie.api.auth.dto.response.WorkspaceSummaryResponse;

import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.UUID;

/**
 * Authentication for a single-organization installation.
 *
 * <p>Login is email + password only: email is never required to authenticate,
 * because a notification channel is optional. A token is scoped to one active
 * workspace; switching workspaces reissues the token pair.
 */
public interface AuthService {

    AuthResponse login(LoginRequest request, HttpServletResponse response);

    /**
     * One-time PKCE code exchange for Keycloak SSO (dual mode with
     * password login). Returns the standard internal credential pair, so every
     * downstream authorization behaves exactly like a password login.
     */
    AuthResponse ssoExchange(SsoExchangeRequest request, HttpServletResponse response);

    AuthResponse refreshToken(String refreshToken, HttpServletResponse response);

    AuthResponse switchWorkspace(
            UUID workspaceId, String refreshToken, HttpServletResponse response);

    AuthResponse switchWorkspace(
            UUID workspaceId, String refreshToken, UUID authenticatedUserId, HttpServletResponse response);

    void logout(String refreshToken);

    void clearRefreshTokenCookie(HttpServletResponse response);

    List<WorkspaceSummaryResponse> listWorkspaces(UUID userId);

    void changePassword(UUID userId, String currentPassword, String newPassword);

    /** Public self-registration; returns credentials for the new account. */
    AuthResponse register(RegisterRequest request, HttpServletResponse response);

    InvitationValidationResponse validateInvitation(String token);

    /** Consumes an invitation and returns credentials for the new account. */
    AuthResponse acceptInvitation(AcceptInvitationRequest request, HttpServletResponse response);
}
