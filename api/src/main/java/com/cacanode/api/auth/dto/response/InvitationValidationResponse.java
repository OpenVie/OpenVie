package com.cacanode.api.auth.dto.response;

import java.time.LocalDateTime;

/**
 * Invitation preview shown before the invitee sets a password.
 *
 * @param workspaceName the workspace the invitation grants access to
 */
public record InvitationValidationResponse(
        String email,
        String organizationName,
        String workspaceName,
        String role,
        LocalDateTime expiresAt) {}
