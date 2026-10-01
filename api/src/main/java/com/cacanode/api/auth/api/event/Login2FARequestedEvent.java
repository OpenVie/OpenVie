package com.cacanode.api.auth.api.event;

import com.cacanode.api.auth.api.Login2FAChallengeType;

import java.util.UUID;

public record Login2FARequestedEvent(
        UUID userId,
        UUID tenantId,
        String email,
        String fullName,
        String verificationSecret,
        Login2FAChallengeType challengeType
) {
    public String getVerificationSecret() {
        return verificationSecret;
    }
}
