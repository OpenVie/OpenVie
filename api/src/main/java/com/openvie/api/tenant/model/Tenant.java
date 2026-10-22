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

import com.openvie.api.common.model.BaseEntity;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A workspace: a department, team, or office whose documents and chats are
 * isolated from every other workspace.
 *
 * <p>The table keeps its historical name {@code tenants} and the isolation
 * column keeps {@code tenant_id}; that is the scoping invariant shared with the
 * hosted product and with the retrieval/gRPC contracts. UI copy says
 * "workspace".
 */
@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TenantStatus status = TenantStatus.ACTIVE;

    /** PUBLIC workspaces are joined automatically by self-registered members. */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private WorkspaceVisibility visibility = WorkspaceVisibility.PUBLIC;

    /** The workspace every account lands in; exactly one per organization. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultWorkspace = false;
}
