package com.cacanode.api.tenant.api;

/**
 * Lifecycle state of a workspace (the table keeps its historical name
 * {@code tenants}).
 */
public enum TenantStatus {
    /** Normal operation: documents, chat, and indexing all work. */
    ACTIVE,

    /** Administrator paused the workspace; data is retained. */
    INACTIVE,

    /**
     * Soft-deleted by the organization owner. Rows stay so audit history
     * remains attributable; the workspace is unreachable from every API.
     */
    ARCHIVED
}
