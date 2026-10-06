package com.cacanode.api.auth.controller;

import com.cacanode.api.auth.dto.request.AcceptInvitationRequest;
import com.cacanode.api.auth.dto.request.ChangePasswordRequest;
import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.request.RegisterRequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.dto.response.InvitationValidationResponse;
import com.cacanode.api.auth.dto.response.WorkspaceSummaryResponse;
import com.cacanode.api.auth.service.AuthService;
import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantIdentityApi.RegistrationStatus;

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
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.login(request, response));
    }

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

    @GetMapping("/workspaces")
    public ResponseEntity<List<WorkspaceSummaryResponse>> workspaces(HttpServletRequest request) {
        return ResponseEntity.ok(authService.listWorkspaces(getUserId(request)));
    }

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
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        return ResponseEntity.status(201)
                .body(authService.register(request, response));
    }

    @GetMapping("/invitations/validate")
    public ResponseEntity<InvitationValidationResponse> validateInvitation(@RequestParam String token) {
        return ResponseEntity.ok(authService.validateInvitation(token));
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<AuthResponse> acceptInvitation(
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.acceptInvitation(request, response));
    }
}
