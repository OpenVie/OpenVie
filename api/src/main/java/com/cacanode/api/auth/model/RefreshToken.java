package com.cacanode.api.auth.model;

import com.cacanode.api.common.model.BaseImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
        }
)
public class RefreshToken extends BaseImmutableEntity {

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Mirrors rememberMe / post-register: persisted cookie vs session cookie (survives rotation). */
    @Column(name = "persistent", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean persistent = false;
}
