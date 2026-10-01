package com.cacanode.api.tenant.model;

import com.cacanode.api.common.model.BaseImmutableEntity;
import com.cacanode.api.tenant.api.InvitationStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An invitation targets one workspace with one workspace role. Delivery
 * requires an enabled notification channel; without one the API refuses to
 * create invitations rather than minting an undeliverable token.
 */
@Getter
@Setter
@Entity
@Table(
        name = "invitations",
        indexes = {
                @Index(name = "idx_invitations_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_invitations_tenant_email", columnList = "tenant_id"),
                @Index(name = "idx_invitations_token_hash", columnList = "token_hash")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "invitations_token_key", columnNames = "token_hash")
        }
)
public class Invitation extends BaseImmutableEntity {

    /** The workspace the invitee joins (historically named tenant). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private WorkspaceRole role = WorkspaceRole.MEMBER;

    @Column(name = "token_hash", unique = true, nullable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;
}
