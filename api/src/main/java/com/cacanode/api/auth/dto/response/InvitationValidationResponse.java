package com.cacanode.api.auth.dto.response;

import java.time.LocalDateTime;

public record InvitationValidationResponse(
        String email,
        String tenantName,
        String role,
        LocalDateTime expiresAt) {}
