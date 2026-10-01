package com.cacanode.api.tenant.model;

import com.cacanode.api.common.model.BaseImmutableEntity;
import com.cacanode.api.tenant.enums.InvitationStatus;
import com.cacanode.api.tenant.enums.UserRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "invitations",
        indexes = {
                @Index(name = "idx_invitation_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_invitation_token_hash", columnList = "token_hash")
        }
)
public class Invitation extends BaseImmutableEntity {

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
    private UserRole role = UserRole.USER;

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
