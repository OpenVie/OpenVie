package com.cacanode.api.tenant.api;

/**
 * Organization-level role carried by a user account.
 *
 * <p>Per-workspace authority lives in {@link WorkspaceRole} on the membership row;
 * an ORG_OWNER governs above workspaces (org settings, workspace lifecycle) and
 * may additionally hold ordinary memberships inside them.
 */
public enum OrgRole {
    ORG_OWNER,
    MEMBER
}
