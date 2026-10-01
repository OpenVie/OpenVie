package com.cacanode.api.auth.service.implement;

import com.cacanode.api.auth.dto.request.LoginRequest;
import com.cacanode.api.auth.dto.request.AcceptInvitationRequest;
import com.cacanode.api.auth.dto.response.AuthResponse;
import com.cacanode.api.auth.dto.response.LoginStep1Response;
import com.cacanode.api.auth.dto.response.ResendVerificationResponse;
import com.cacanode.api.auth.dto.response.InvitationValidationResponse;
import com.cacanode.api.auth.api.Login2FAChallengeType;
import com.cacanode.api.auth.model.Login2FAState;
import com.cacanode.api.auth.model.RefreshToken;
import com.cacanode.api.auth.repository.Login2FAStateRepository;
import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.event.durable.DurableEventPublisher;
import com.cacanode.api.auth.api.event.Login2FARequestedEvent;
import io.jsonwebtoken.Claims;
import com.cacanode.api.auth.repository.RefreshTokenRepository;
import com.cacanode.api.auth.service.AuthService;
import com.cacanode.api.auth.service.Login2FAAttemptService;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.auth.service.JwtService;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantUserResult;
import com.cacanode.api.tenant.api.UserAuthDto;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j(topic = "AUTH-SERVICE")
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final int MOBILE_CODE_BOUND = 1_000_000;
    private static final int MAX_MOBILE_CODE_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${jwt.expiry-days}")
    private Long refreshTokenExpiryTime;

    @Value("${spring.sendgrid.support-email:}")
    private String supportEmail;

    @Value("${spring.sendgrid.verification-resend-cooldown-seconds:60}")
    private Integer verificationResendCooldownSeconds;

    @Value("${spring.sendgrid.max-verification-resend-attempts:5}")
    private Integer maxVerificationResendAttempts;

    @Value("${spring.sendgrid.login-2fa-expiry-minutes:15}")
    private Integer login2FAExpiryMinutes;

    @Value("${spring.sendgrid.mobile-login-2fa-expiry-minutes:10}")
    private Integer mobileLogin2FAExpiryMinutes;

    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${app.security.login-2fa-bypass-emails:}")
    private String login2FABypassEmails;

    private final TenantIdentityApi tenantModuleApi;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Login2FAStateRepository login2FAStateRepository;
    private final JwtService jwtService;
    private final Login2FAAttemptService login2FAAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private DurableEventPublisher durableEventPublisher;
    @Override
    @Transactional(readOnly = true)
    public InvitationValidationResponse validateInvitation(String token) {
        var invitation = tenantModuleApi.validateInvitation(token);
        return new InvitationValidationResponse(
                invitation.email(), invitation.tenantName(), invitation.role(), invitation.expiresAt());
    }

    @Override
    @Transactional
    public AuthResponse acceptInvitation(AcceptInvitationRequest request, HttpServletResponse response) {
        var accepted = tenantModuleApi.acceptInvitation(
                request.getToken(), request.getFullName(),
                passwordEncoder.encode(request.getPassword()));
        UserAuthDto user = UserAuthDto.builder()
                .userId(accepted.userId()).tenantId(accepted.tenantId()).email(accepted.email())
                .fullName(accepted.fullName()).role(accepted.role()).status(accepted.status())
                .passwordHash(accepted.passwordHash()).build();
        return issueAuthTokens(user, response, true);
    }


    @Override
    @Transactional
    public Object login(LoginRequest req, HttpServletResponse res) {
        LoginOutcome outcome = beginLogin(req.getEmail(), req.getPassword(), Login2FAChallengeType.LINK);
        if (outcome.challenge() != null) {
            return outcome.challenge();
        }

        return deliverBrowserCredentials(issueCredentialPair(outcome.user(), req.isRememberMe()), res);
    }

    private String supportContactSuffix() {
        if (supportEmail == null || supportEmail.isBlank()) {
            return "";
        }
        return " Please contact " + supportEmail.trim() + " for assistance.";
    }

    private LoginOutcome beginLogin(
            String email, String password, Login2FAChallengeType challengeType) {
        TenantUserResult result = tenantModuleApi.authenticateUser(email, password);

        if (result == null) {
            throw new ConflictException("Invalid email or password");
        }

        if ("SUSPENDED".equals(result.getStatus())) {
            throw new UnauthorizedException("Account suspended." + supportContactSuffix());
        }
        if (!"ACTIVE".equals(result.getStatus())) {
            throw new UnauthorizedException("User account is disabled");
        }
        Login2FAState existingState = login2FAStateRepository.findByEmail(result.getEmail())
                .orElse(null);
        if (isLogin2FABypassed(result.getEmail())) {
            UserAuthDto user = tenantModuleApi.findUserById(result.getUserId());
            validateActiveUser(user, result.getUserId(), result.getTenantId());

            log.info("Login 2FA bypassed for configured dev account: userId={}, email={}",
                    result.getUserId(), result.getEmail());
            return new LoginOutcome(null, user);
        }

        String verificationSecret = createLoginChallengeSecret(
                challengeType, result.getUserId(), result.getEmail());

        if (existingState == null) {
            existingState = new Login2FAState();
            existingState.setUserId(result.getUserId());
            existingState.setEmail(result.getEmail());
            existingState.setAttemptCount(1);
        } else {
            existingState.setAttemptCount(existingState.getAttemptCount() + 1);
        }
        existingState.setChallengeType(challengeType);
        existingState.setTokenHash(hashLoginChallenge(challengeType, verificationSecret));
        existingState.setExpiresAt(LocalDateTime.now().plusMinutes(challengeExpiryMinutes(challengeType)));
        existingState.setUsed(false);
        existingState.setVerificationAttemptCount(0);
        login2FAStateRepository.save(existingState);

        publishBusinessEvent("auth.login-2fa.requested.v1",
                new Login2FARequestedEvent(result.getUserId(), result.getTenantId(),
                        result.getEmail(), result.getFullName(), verificationSecret, challengeType));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("attemptCount", existingState.getAttemptCount());
        metadata.put("email", result.getEmail());
        eventPublisher.publishEvent(
                AuditLogEvent.builder(this)
                        .tenantId(result.getTenantId())
                        .userId(result.getUserId())
                        .action(LogAction.LOGIN_2FA_REQUESTED)
                        .resourceType("login_2fa")
                        .resourceId(existingState.getId())
                        .metadata(metadata)
                        .build());

        log.info("Login 2FA requested: userId={}, email={}", result.getUserId(), result.getEmail());

        LoginStep1Response challenge = LoginStep1Response.builder()
                .message(challengeType == Login2FAChallengeType.CODE
                        ? "Please check your email for a six-digit confirmation code."
                        : "Please check your email for a verification link to complete login.")
                .email(result.getEmail())
                .requires2FA(true)
                .build();
        return new LoginOutcome(challenge, null);
    }

    private String createLoginChallengeSecret(
            Login2FAChallengeType challengeType, UUID userId, String email) {
        if (challengeType == Login2FAChallengeType.CODE) {
            return String.format("%06d", SECURE_RANDOM.nextInt(MOBILE_CODE_BOUND));
        }
        return jwtService.generateVerificationToken(userId, email);
    }

    private String hashLoginChallenge(Login2FAChallengeType challengeType, String secret) {
        return challengeType == Login2FAChallengeType.CODE
                ? passwordEncoder.encode(secret)
                : jwtService.hashToken(secret);
    }

    private int challengeExpiryMinutes(Login2FAChallengeType challengeType) {
        return challengeType == Login2FAChallengeType.CODE
                ? mobileLogin2FAExpiryMinutes
                : login2FAExpiryMinutes;
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = jwtService.hashToken(refreshToken);
        refreshTokenRepository.revokeByTokenHash(tokenHash);
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(String refreshToken, HttpServletResponse res) {
        CredentialPair credentials = rotateCredentials(refreshToken, false);
        return deliverBrowserCredentials(credentials, res);
    }

    private CredentialPair rotateCredentials(String refreshToken, boolean genericErrors) {
        String tokenHash = jwtService.hashToken(refreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> refreshFailure(genericErrors, "Invalid refresh token"));

        if (stored.isRevoked()) {
            throw refreshFailure(genericErrors, "Refresh token revoked");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!stored.getExpiresAt().isAfter(now)) {
            throw refreshFailure(genericErrors, "Refresh token expired");
        }

        UserAuthDto user = tenantModuleApi.findUserById(stored.getUserId());
        try {
            validateActiveUser(user, stored.getUserId(), stored.getTenantId());
        } catch (UnauthorizedException exception) {
            throw refreshFailure(genericErrors, exception.getMessage());
        }

        boolean persistent = stored.isPersistent();
        int consumed = refreshTokenRepository.consumeActiveToken(stored.getId(), tokenHash, now);
        if (consumed != 1) {
            throw refreshFailure(genericErrors, "Invalid refresh token");
        }

        return issueCredentialPair(user, persistent);
    }

    private UnauthorizedException refreshFailure(boolean genericErrors, String browserMessage) {
        return new UnauthorizedException(genericErrors ? "Invalid refresh token" : browserMessage);
    }

    @Override
    public boolean isEmailExist(String email) {
        return tenantModuleApi.existsByEmail(email);
    }

    @Override
    public void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, boolean persistent) {
        Duration maxAge = persistent
                ? Duration.ofDays(refreshTokenExpiryTime)
                : Duration.ofSeconds(-1);
        ResponseCookie cookie = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api")
                .maxAge(maxAge)
                .sameSite("Strict")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api")
                .maxAge(0) // ← 0 deletes the cookie
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    @Transactional
    public AuthResponse verifyLogin2FA(String token, HttpServletResponse res) {
        UserAuthDto user = verifyLogin2FALinkUser(token);
        return deliverBrowserCredentials(issueCredentialPair(user, true), res);
    }

    private UserAuthDto verifyLogin2FALinkUser(String token) {
        Claims claims = jwtService.validateVerificationToken(token);
        UUID userId = UUID.fromString(claims.get("userId", String.class));
        String email = claims.getSubject();

        Login2FAState state = login2FAStateRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired login verification"));

        if (state.getChallengeType() != Login2FAChallengeType.LINK) {
            throw new UnauthorizedException("Invalid or expired login verification");
        }

        if (!userId.equals(state.getUserId())) {
            throw new UnauthorizedException("Invalid verification token");
        }

        if (state.isUsed()) {
            throw new UnauthorizedException("This verification link has already been used");
        }

        if (!state.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new UnauthorizedException("Login verification link has expired");
        }

        String tokenHash = jwtService.hashToken(token);
        if (!tokenHash.equals(state.getTokenHash())) {
            throw new UnauthorizedException("Invalid verification token");
        }

        UserAuthDto user = tenantModuleApi.findUserById(userId);
        validateActiveUser(user, userId, null);

        if (login2FAStateRepository.consumeIfActive(state.getId(), LocalDateTime.now()) != 1) {
            throw new UnauthorizedException("This verification link has already been used");
        }

        publishLogin2FAVerified(state, user, email);
        return user;
    }

    
    
    
    private void publishLogin2FAVerified(Login2FAState state, UserAuthDto user, String email) {
        UUID userId = state.getUserId();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("attemptCount", state.getAttemptCount());
        metadata.put("email", email);
        eventPublisher.publishEvent(
                AuditLogEvent.builder(this)
                        .tenantId(user.getTenantId())
                        .userId(userId)
                        .action(LogAction.LOGIN_2FA_VERIFIED)
                        .resourceType("login_2fa")
                        .resourceId(state.getId())
                        .metadata(metadata)
                        .build());

        log.info("Login 2FA verified: userId={}, email={}", userId, email);
    }

    private boolean isLogin2FABypassed(String email) {
        if (login2FABypassEmails == null || login2FABypassEmails.isBlank()) {
            return false;
        }

        for (String allowedEmail : login2FABypassEmails.split(",")) {
            if (allowedEmail.trim().equalsIgnoreCase(email)) {
                return true;
            }
        }

        return false;
    }

    @Override
    @Transactional
    public AuthResponse issueAuthTokens(UserAuthDto user, HttpServletResponse res, boolean persistent) {
        if (user == null) {
            throw new UnauthorizedException("User account is disabled");
        }
        validateActiveUser(user, user.getUserId(), user.getTenantId());
        return deliverBrowserCredentials(issueCredentialPair(user, persistent), res);
    }

    private CredentialPair issueCredentialPair(UserAuthDto user, boolean persistent) {
        String accessToken = jwtService.generateAccessToken(
                user.getUserId(),
                user.getTenantId(),
                user.getEmail(),
                user.getRole());
        String refreshTokenValue = jwtService.generateRefreshToken();

        // 12. Persist refresh token - auth module owns refresh_tokens table
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getUserId());
        refreshToken.setTenantId(user.getTenantId());
        refreshToken.setTokenHash(jwtService.hashToken(refreshTokenValue));
        refreshToken.setExpiresAt(LocalDateTime.now().plusDays(refreshTokenExpiryTime));
        refreshToken.setRevoked(false);
        refreshToken.setPersistent(persistent);
        refreshTokenRepository.save(refreshToken);

        return new CredentialPair(
                accessToken,
                refreshTokenValue,
                jwtService.getAccessTokenExpirySeconds(),
                persistent,
                toUserInfo(user));
    }

    private AuthResponse deliverBrowserCredentials(CredentialPair credentials, HttpServletResponse res) {
        setRefreshTokenCookie(res, credentials.refreshToken(), credentials.persistent());
        return AuthResponse.builder()
                .accessToken(credentials.accessToken())
                .tokenType("Bearer")
                .expiresIn(credentials.expiresIn())
                .user(credentials.user())
                .build();
    }

    
    private AuthResponse.UserInfo toUserInfo(UserAuthDto user) {
        return AuthResponse.UserInfo.builder()
                .userId(user.getUserId().toString())
                .tenantId(user.getTenantId().toString())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .build();
    }

    private void validateActiveUser(UserAuthDto user, UUID expectedUserId, UUID expectedTenantId) {
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new UnauthorizedException("User account is disabled");
        }
        if (user.getUserId() == null || user.getTenantId() == null
                || !user.getUserId().equals(expectedUserId)
                || (expectedTenantId != null && !user.getTenantId().equals(expectedTenantId))) {
            throw new UnauthorizedException("Refresh token scope is invalid");
        }
    }

    @Override
    @Transactional
    public ResendVerificationResponse resendLogin2FA(String email) {
        // 1. Find existing 2FA state
        Login2FAState state = login2FAStateRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("No pending login verification found"));
        Login2FAChallengeType challengeType = state.getChallengeType();

        // 2. Check if already used
        if (state.isUsed()) {
            throw new UnauthorizedException("Login verification already completed");
        }

        // 4. Get user info
        UserAuthDto user = tenantModuleApi.findUserById(state.getUserId());
        if (user == null) {
            throw new UnauthorizedException("User not found");
        }
        UUID tenantId = user.getTenantId();
        UUID userId = state.getUserId();

        // 5. Check attempt limits
        if (state.getAttemptCount() >= maxVerificationResendAttempts) {
            refreshTokenRepository.revokeAllByUserId(userId);

            // Publish audit log event
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("attemptCount", state.getAttemptCount());
            metadata.put("email", email);
            metadata.put("reason", "LOGIN_2FA_RESEND_ABUSE");
            eventPublisher.publishEvent(
                    AuditLogEvent.builder(this)
                            .tenantId(tenantId)
                            .userId(userId)
                            .action(LogAction.USER_SUSPENDED_VERIFICATION_ABUSE)
                            .resourceType("user_suspension")
                            .metadata(metadata)
                            .build());

            log.warn("User suspended due to login 2FA abuse: userId={}, email={}", userId, email);
            throw new UnauthorizedException(
                    "Account suspended due to verification abuse." + supportContactSuffix());
        }

        // 6. Check cooldown (use createdAt for cooldown calculation)
        java.time.Duration sinceLastAttempt = java.time.Duration.between(
                state.getUpdatedAt(), LocalDateTime.now());
        if (sinceLastAttempt.getSeconds() < verificationResendCooldownSeconds) {
            int remainingSeconds = verificationResendCooldownSeconds - (int) sinceLastAttempt.getSeconds();

            // Publish audit log for rate limit
            Map<String, Object> rateLimitMetadata = new HashMap<>();
            rateLimitMetadata.put("attemptCount", state.getAttemptCount());
            rateLimitMetadata.put("email", email);
            rateLimitMetadata.put("remainingSeconds", remainingSeconds);
            eventPublisher.publishEvent(
                    AuditLogEvent.builder(this)
                            .tenantId(tenantId)
                            .userId(userId)
                            .action(LogAction.LOGIN_2FA_RESEND_LIMIT_EXCEEDED)
                            .resourceType("login_2fa")
                            .metadata(rateLimitMetadata)
                            .build());

            return ResendVerificationResponse.builder()
                    .message(challengeType == Login2FAChallengeType.CODE
                            ? "Please wait before requesting another confirmation code"
                            : "Please wait before requesting another verification email")
                    .canRetryAfterSeconds(remainingSeconds)
                    .build();
        }

        // 7. Generate a replacement challenge of the same type.
        String verificationSecret = createLoginChallengeSecret(challengeType, userId, email);

        // 8. Update state
        state.setAttemptCount(state.getAttemptCount() + 1);
        state.setTokenHash(hashLoginChallenge(challengeType, verificationSecret));
        state.setExpiresAt(LocalDateTime.now().plusMinutes(challengeExpiryMinutes(challengeType)));
        state.setVerificationAttemptCount(0);
        state.setUsed(false);
        login2FAStateRepository.save(state);

        // 9. Publish event to send 2FA email
        publishBusinessEvent("auth.login-2fa.requested.v1",
                new Login2FARequestedEvent(userId, tenantId,
                        email, user.getFullName(), verificationSecret, challengeType));

        // 10. Publish audit log event
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("attemptCount", state.getAttemptCount());
        metadata.put("email", email);
        eventPublisher.publishEvent(
                AuditLogEvent.builder(this)
                        .tenantId(tenantId)
                        .userId(userId)
                        .action(LogAction.LOGIN_2FA_RESEND_REQUESTED)
                        .resourceType("login_2fa")
                        .resourceId(state.getId())
                        .metadata(metadata)
                        .build());

        log.info("Login 2FA resent: userId={}, email={}, attempt={}", userId, email,
                state.getAttemptCount());

        return ResendVerificationResponse.builder()
                .message(challengeType == Login2FAChallengeType.CODE
                        ? "A new confirmation code was sent. Please check your inbox."
                        : "Verification email sent. Please check your inbox.")
                .canRetryAfterSeconds(verificationResendCooldownSeconds)
                .build();
    }

    private record LoginOutcome(LoginStep1Response challenge, UserAuthDto user) {
    }

    private void publishBusinessEvent(String stableType, Object event) {
        if (durableEventPublisher != null) {
            durableEventPublisher.publish(stableType, 1, event);
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private record CredentialPair(
            String accessToken,
            String refreshToken,
            long expiresIn,
            boolean persistent,
            AuthResponse.UserInfo user) {
    }
}
