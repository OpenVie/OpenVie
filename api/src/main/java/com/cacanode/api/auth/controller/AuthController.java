package com.cacanode.api.auth.controller;

import com.cacanode.api.auth.dto.request.AcceptInvitationRequest;
import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.request.ResendLogin2FARequest;
import com.cacanode.api.auth.dto.request.VerifyLogin2FARequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.dto.response.InvitationValidationResponse;
import com.cacanode.api.auth.dto.response.ResendVerificationResponse;
import com.cacanode.api.auth.service.AuthService;
import com.cacanode.api.common.exception.custom.UnauthorizedException;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Endpoints for user registration, login, token refresh, and logout")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

        private final AuthService authService;

        @GetMapping("/invitations/validate")
        public InvitationValidationResponse validateInvitation(@RequestParam String token) {
                return authService.validateInvitation(token);
        }

        @PostMapping("/invitations/accept")
        public ResponseEntity<AuthResponse> acceptInvitation(
                        @Valid @RequestBody AcceptInvitationRequest request,
                        HttpServletResponse response) {
                return ResponseEntity.ok(authService.acceptInvitation(request, response));
        }

        
        
        
        @PostMapping("/login")
        public ResponseEntity<Object> login(
                        @Valid @RequestBody LoginRequest request,
                        HttpServletResponse response) {
                Object result = authService.login(request, response);
                return ResponseEntity.ok(result);
        }

        @PostMapping("/verify-login-2fa")
        public ResponseEntity<AuthResponse> verifyLogin2FA(
                        @Valid @RequestBody VerifyLogin2FARequest request,
                        HttpServletResponse response) {
                return ResponseEntity.ok(authService.verifyLogin2FA(request.getToken(), response));
        }

        @PostMapping("/resend-login-2fa")
        public ResponseEntity<ResendVerificationResponse> resendLogin2FA(
                        @Valid @RequestBody ResendLogin2FARequest request) {
                return ResponseEntity.ok(authService.resendLogin2FA(request.getEmail()));
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
}
