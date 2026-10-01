package com.cacanode.api.tenant.model;

import com.cacanode.api.common.model.BaseEntity;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.enums.WorkspaceVisibility;
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
