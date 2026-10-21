package com.openvie.api.integration.apikey;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Machine-to-machine credential scoped to one workspace.
 *
 * <p>Only the SHA-256 hash of the presented secret is stored. The key
 * authenticates as the provisioned service-member user ({@link #userId}),
 * so audit records, document uploads, and sessions carry a real user id.
 */
@Getter
@Setter
@Entity
@Table(name = "api_keys")
public class ApiKey extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Workspace the key operates in. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Service-member user row this key authenticates as. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** `ovk_` + first 8 secret characters — for identifying keys in UIs. */
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(name = "role", nullable = false, length = 32)
    private String role = "MEMBER";

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /** Safe value for listings: the prefix, never the secret. */
    public String maskedKey() {
        return keyPrefix + "…";
    }
}
