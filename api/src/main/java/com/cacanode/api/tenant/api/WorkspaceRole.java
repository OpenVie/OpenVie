package com.cacanode.api.tenant.api;

/**
 * Authority of a user inside one workspace, stored on the membership row.
 * The historical tenant vocabulary is retained internally: a workspace row
 * lives in the {@code tenants} table and is addressed by {@code tenant_id}.
 */
public enum WorkspaceRole {
    WORKSPACE_ADMIN,
    MEMBER
}
