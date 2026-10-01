package com.cacanode.api.tenant.service;

import com.cacanode.api.common.enums.LogAction;
import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantCredentials;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Owns every write to {@code users.password_hash} and the password rules that
 * guard them. Published through {@link TenantCredentials} so other modules
 * never touch the users table or duplicate the strength policy.
 */
@Service
@Slf4j(topic = "CREDENTIALS")
@RequiredArgsConstructor
public class TenantCredentialService implements TenantCredentials {

    /** Development seed password; never acceptable as a real credential. */
    static final String KNOWN_DEV_PASSWORD = "Cacanode@123";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String hashNewPassword(String password) {
        validateStrength(password);
        return passwordEncoder.encode(password);
    }

    /** Validates a candidate password; throws with the exact rule violated. */
    static void validateStrength(String password) {
        if (password == null || password.isBlank()) {
            throw new BadRequestException("Password is required");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (KNOWN_DEV_PASSWORD.equals(password)) {
            throw new BadRequestException("That is the published development password; choose a fresh one");
        }
    }

    /**
     * Self-service change. The current password is always required, including
     * for an account whose initial password was set by an administrator:
     * presenting it once proves possession, and the forced-change flag clears.
     */
    @Override
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = require(userId);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        validateStrength(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .userId(userId)
                .action(LogAction.USER_PASSWORD_CHANGED)
                .resourceType("user")
                .resourceId(userId)
                .metadata(Map.of())
                .build());
        log.info("Password changed: userId={}", userId);
    }

    @Override
    @Transactional
    public void setInitialPassword(UUID actorId, UUID userId, String newPassword) {
        User user = require(userId);
        validateStrength(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .userId(actorId)
                .action(LogAction.USER_PASSWORD_RESET)
                .resourceType("user")
                .resourceId(userId)
                .metadata(Map.of("forcedChange", true))
                .build());
        log.info("Administrator set password: actorId={}, userId={}", actorId, userId);
    }

    private User require(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /** True when the account is the organization's only usable owner. */
    @Transactional(readOnly = true)
    public boolean isFinalOrgOwner(User user) {
        return user.getRole() == OrgRole.ORG_OWNER
                && user.getStatus() == UserStatus.ACTIVE
                && userRepository.countByOrganization_IdAndRoleAndStatus(
                        user.getOrganization().getId(), OrgRole.ORG_OWNER, UserStatus.ACTIVE) <= 1;
    }

    static String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        String normalized = Normalizer.normalize(email.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return Pattern.compile("\\p{InCOMBINING_DIACRITICAL_MARKS}+").matcher(normalized).replaceAll("");
    }
}
