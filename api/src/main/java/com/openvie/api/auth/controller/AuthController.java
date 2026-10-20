package com.openvie.api.auth.controller;

import com.openvie.api.auth.dto.request.AcceptInvitationRequest;
import com.openvie.api.auth.dto.request.ChangePasswordRequest;
import com.openvie.api.auth.dto.request.LoginRequest;
import com.openvie.api.auth.dto.request.SsoExchangeRequest;
import com.openvie.api.auth.dto.request.RegisterRequest;
import com.openvie.api.auth.dto.response.AuthResponse;
import com.openvie.api.auth.dto.response.InvitationValidationResponse;
import com.openvie.api.auth.dto.response.WorkspaceSummaryResponse;
import com.openvie.api.auth.service.AuthService;
import com.openvie.api.common.controller.BaseController;
import com.openvie.api.common.exception.custom.UnauthorizedException;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantIdentityApi.RegistrationStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Authentication",
        description = "Login, token refresh, workspace switching, registration, and invitations")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController extends BaseController {

    private final AuthService authService;
    private final TenantIdentityApi identityApi;

    /**
     * Login is email and password only. Email is never required to
     * authenticate, because a notification channel is optional.
     */
    @Operation(summary = "Password login", description = "Exchanges email and password for the internal credential pair; "
            + "the refresh token rides an HttpOnly cookie. Public endpoint.")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.login(request, response));
    }

    /**
     * One-time PKCE code exchange for Keycloak SSO.
     * Returns the standard internal credential pair, so the frontend session
     * handling stays identical to password login.
     */
    @Operation(summary = "Exchange a Keycloak SSO code",
            description = "One-time PKCE code exchange for Keycloak SSO; returns the standard internal credential pair so "
                    + "frontend session handling stays identical to password login. Public endpoint.")
    @PostMapping("/sso/exchange")
    public ResponseEntity<AuthResponse> ssoExchange(
            @Valid @RequestBody SsoExchangeRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.ssoExchange(request, response));
    }

    @Operation(summary = "Rotate credentials using the refresh cookie",
            description = "Reads the HttpOnly refresh_token cookie and re-issues the credential pair (roles re-resolved). "
            + "Public endpoint; requires no bearer token.")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            throw new UnauthorizedException("Refresh token missing");
        }
        return ResponseEntity.ok(authService.refreshToken(refreshToken, response));
    }

    /** Re-issues credentials scoped to another workspace the caller belongs to. */
    @Operation(summary = "Switch workspace",
            description = "Re-issues credentials scoped to another workspace the refresh token (or current bearer, to "
                    + "verify membership) already belongs to. Public endpoint backed by the refresh cookie.")
    @PostMapping("/workspaces/switch")
    public ResponseEntity<AuthResponse> switchWorkspace(
            @RequestBody Map<String, String> body,
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        String raw = body.get("workspaceId");
        if (raw == null || raw.isBlank()) {
            throw new UnauthorizedException("workspaceId is required");
        }
        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid workspaceId");
        }
        UUID authenticatedUserId = null;
        try {
            authenticatedUserId = getUserId(request);
        } catch (RuntimeException ignored) {
        }
        return ResponseEntity.ok(authService.switchWorkspace(
                workspaceId, refreshToken, authenticatedUserId, response));
    }

    @Operation(summary = "List the workspaces of the caller",
            description = "The full membership list used by the workspace switcher. Requires a bearer token.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/workspaces")
    public ResponseEntity<List<WorkspaceSummaryResponse>> workspaces(HttpServletRequest request) {
        return ResponseEntity.ok(authService.listWorkspaces(getUserId(request)));
    }

    @Operation(summary = "Logout",
            description = "Revokes the refresh token (when present) and clears the refresh cookie. Public endpoint.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        authService.clearRefreshTokenCookie(response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Self-service password change. Also completes the forced change that an
     * administrator-set initial password requires.
     */
    @Operation(summary = "Change password (self-service)",
            description = "Changes the caller's own password and completes the forced change that an administrator-set "
                    + "initial password requires. Requires a bearer token.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpServletRequest) {
        authService.changePassword(getUserId(httpServletRequest),
                request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    // ─── Public account entry points ──────────────────────────────────────────

    /** Lets the web client choose between the setup form, login, and register. */
    @Operation(summary = "Registration status",
            description = "Tells the web client to show the one-time setup form, login, or registration. Public endpoint.")
    @GetMapping("/registration-status")
    public ResponseEntity<Map<String, Object>> registrationStatus() {
        RegistrationStatus status = identityApi.registrationStatus();
        return ResponseEntity.ok(Map.of(
                "setupRequired", status.setupRequired(),
                "selfRegistrationAllowed", status.selfRegistrationAllowed()));
    }

    /**
     * Creates an account when the organization allows self-registration. The
     * account joins the default workspace and every public workspace.
     */
    @Operation(summary = "Self-registration",
            description = "Creates an account when the organization allows self-registration; the account joins the "
            + "default workspace and every public workspace. Public endpoint.")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        return ResponseEntity.status(201)
                .body(authService.register(request, response));
    }

    @Operation(summary = "Validate an invitation token",
            description = "Read-only check for the invitation landing page. Public endpoint.")
    @GetMapping("/invitations/validate")
    public ResponseEntity<InvitationValidationResponse> validateInvitation(@RequestParam String token) {
        return ResponseEntity.ok(authService.validateInvitation(token));
    }

    @Operation(summary = "Accept an invitation",
            description = "Creates the member account from the invitation and issues the credential pair. Public endpoint.")
    @PostMapping("/invitations/accept")
    public ResponseEntity<AuthResponse> acceptInvitation(
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.acceptInvitation(request, response));
    }
}
