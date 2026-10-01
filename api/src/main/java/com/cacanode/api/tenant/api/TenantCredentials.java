package com.cacanode.api.tenant.api;

import java.util.UUID;

/**
 * Password rules and credential writes, published so other modules never touch
 * the users table or re-implement the strength policy.
 *
 * <p>Setup, self-registration, invitation acceptance, administrator-set initial
 * passwords, and self-service changes all pass through this one gate.
 */
public interface TenantCredentials {

    int MIN_PASSWORD_LENGTH = 12;

    /**
     * Validates strength and returns the digest to persist.
     *
     * @throws com.cacanode.api.common.exception.custom.BadRequestException when
     *             the password is short, blank, or the published dev password
     */
    String hashNewPassword(String password);

    /** Self-service change; clears any forced-change flag. */
    void changePassword(UUID userId, String currentPassword, String newPassword);

    /**
     * Administrator reset: no current password required, and the account must
     * change it on next login.
     */
    void setInitialPassword(UUID actorId, UUID userId, String newPassword);
}
