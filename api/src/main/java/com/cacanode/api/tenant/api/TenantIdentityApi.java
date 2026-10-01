package com.cacanode.api.tenant.api;

import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.WorkspaceRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Identity, membership, and account-creation contract owned by the tenant
 * module.
 *
 * <p>Authority is split: an {@link OrgRole} belongs to the organization, a
 * {@link WorkspaceRole} belongs to one workspace through a membership row.
 * Nothing here trusts a caller-supplied workspace; every workspace decision
 * flows through {@link #requireMembership}, which is re-resolved from the
 * database on each request.
 *
 * <p>The interface keeps its historical name so the hosted repository, which
 * shares this isolation engine, stays mergeable.
 */
public interface TenantIdentityApi {

    /**
     * Resolves credentials to an identity plus the workspace to land in.
     *
     * @return null when the email/password pair does not authenticate
     * @throws com.cacanode.api.common.exception.custom.UnauthorizedException
     *             when the password matches but the account cannot sign in
     */
    AuthenticatedIdentity authenticate(String email, String password);

    IdentitySnapshot findUserById(UUID userId);

    /** True once setup has created the first account. */
    boolean accountExists();

    /**
     * What the public web client may offer: the setup form while no account
     * exists, then registration only if the organization enabled it.
     */
    RegistrationStatus registrationStatus();

    /**
     * The caller's effective authority inside one workspace.
     *
     * @throws com.cacanode.api.common.exception.custom.UnauthorizedException
     *             when the user is not a member of that workspace, or the
     *             account or organization is not usable
     */
    MembershipSnapshot requireMembership(UUID userId, UUID workspaceId);

    /** Workspaces the user may switch into, default first. */
    List<WorkspaceSummary> listMemberships(UUID userId);

    /**
     * Self-registration, honored only while the organization enables it. The
     * new account joins the default workspace and every PUBLIC workspace.
     *
     * @throws com.cacanode.api.common.exception.custom.ConflictException
     *             when self-registration is disabled or the email is taken
     */
    AcceptedAccount register(String email, String fullName, String passwordHash);

    /** Validates an invitation token without consuming it. */
    InvitationSnapshot validateInvitation(String rawToken);

    /** Consumes an invitation token and creates the account it targets. */
    AcceptedAccount acceptInvitation(String rawToken, String fullName, String passwordHash);

    record IdentitySnapshot(
            UUID userId,
            UUID orgId,
            String email,
            String fullName,
            OrgRole orgRole,
            String status,
            boolean mustChangePassword) {
    }

    /**
     * @param defaultWorkspaceId workspace to enter after login: the default
     *                           workspace, or the only one the user belongs to
     * @param defaultWorkspaceRole the user's role inside that workspace
     */
    record AuthenticatedIdentity(
            IdentitySnapshot identity,
            UUID defaultWorkspaceId,
            WorkspaceRole defaultWorkspaceRole) {
    }

    record MembershipSnapshot(
            UUID userId,
            UUID orgId,
            UUID workspaceId,
            OrgRole orgRole,
            WorkspaceRole workspaceRole,
            String status) {

        /** Organization owners act with admin authority wherever they belong. */
        public boolean isWorkspaceAdmin() {
            return workspaceRole == WorkspaceRole.WORKSPACE_ADMIN || orgRole == OrgRole.ORG_OWNER;
        }
    }

    record WorkspaceSummary(
            UUID workspaceId, String name, String slug, WorkspaceRole role, boolean isDefault) {
    }

    record InvitationSnapshot(
            String email,
            String organizationName,
            String workspaceName,
            WorkspaceRole role,
            LocalDateTime expiresAt) {
    }

    record AcceptedAccount(
            UUID userId,
            UUID orgId,
            UUID workspaceId,
            String email,
            String fullName,
            OrgRole orgRole,
            WorkspaceRole workspaceRole,
            String status,
            boolean mustChangePassword) {
    }

    /**
     * @param setupRequired no account exists yet; the web client shows the
     *                      one-time setup form
     * @param selfRegistrationAllowed accounts may be created by their owners
     *                               through the public register endpoint
     */
    record RegistrationStatus(boolean setupRequired, boolean selfRegistrationAllowed) {
    }
}
