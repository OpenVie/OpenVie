// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.tenant.model;

import com.openvie.api.common.model.BaseImmutableEntity;
import com.openvie.api.tenant.api.InvitationStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
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
