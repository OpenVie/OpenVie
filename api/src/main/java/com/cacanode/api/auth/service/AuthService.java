package com.cacanode.api.auth.service;

import com.cacanode.api.auth.dto.request.AcceptInvitationRequest;
import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.request.RegisterRequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.dto.response.InvitationValidationResponse;
import com.cacanode.api.auth.dto.response.WorkspaceSummaryResponse;

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
