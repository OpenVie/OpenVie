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
import com.openvie.api.tenant.api.WorkspaceRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One row per (user, workspace) pair. The effective authority of a request is
 * the role on this row for the token's active workspace; controllers never
 * read {@code users.role} for workspace decisions.
 */
@Getter
@Setter
@Entity
@Table(
        name = "workspace_members",
        indexes = {
                @Index(name = "idx_workspace_members_user_id", columnList = "user_id"),
                @Index(name = "idx_workspace_members_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_workspace_members_tenant_role", columnList = "tenant_id,role")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_workspace_member", columnNames = {"user_id", "tenant_id"})
        }
)
public class WorkspaceMember extends BaseImmutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** The workspace (historically "tenant") this membership grants access to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private Tenant workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private WorkspaceRole role = WorkspaceRole.MEMBER;

    @Transient
    public UUID userId() {
        return user.getId();
    }

    @Transient
    public UUID workspaceId() {
        return workspace.getId();
    }
}
