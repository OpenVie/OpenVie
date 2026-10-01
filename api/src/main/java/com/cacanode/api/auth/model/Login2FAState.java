package com.cacanode.api.auth.model;

import com.cacanode.api.auth.api.Login2FAChallengeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cacanode.api.common.model.BaseEntity;

@Getter
@Setter
@Entity
@Table(name = "login_2fa_state", indexes = {
        @Index(name = "idx_login_2fa_user_id", columnList = "user_id"),
        @Index(name = "idx_login_2fa_token_hash", columnList = "token_hash"),
        @Index(name = "idx_login_2fa_expires_at", columnList = "expires_at")
})
public class Login2FAState extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used", nullable = false)
    @Getter(AccessLevel.NONE)
    private boolean used = false;

    public boolean isUsed() {
        return used;
    }

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "challenge_type", nullable = false, length = 16)
    private Login2FAChallengeType challengeType = Login2FAChallengeType.LINK;

    @Column(name = "verification_attempt_count", nullable = false)
    private Integer verificationAttemptCount = 0;

    @PrePersist
    protected void onCreate() {
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (challengeType == null) {
            challengeType = Login2FAChallengeType.LINK;
        }
        if (verificationAttemptCount == null) {
            verificationAttemptCount = 0;
        }
    }
}
