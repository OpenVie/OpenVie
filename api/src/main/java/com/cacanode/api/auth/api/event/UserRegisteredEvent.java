package com.cacanode.api.auth.api.event;

import java.util.UUID;
import java.time.LocalDateTime;

public record UserRegisteredEvent(
        UUID userId,
        UUID tenantId,
        String email,
        String fullName,
        String companyName,
        String verificationToken,
        String role,
        String status,
        LocalDateTime createdAt
) {
    public UserRegisteredEvent(UUID userId, UUID tenantId, String email, String fullName,
                               String companyName, String verificationToken) {
        this(userId, tenantId, email, fullName, companyName, verificationToken,
                "TENANT_ADMIN", "PENDING", LocalDateTime.now());
    }
}
