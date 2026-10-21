package com.openvie.api.tenant.dto;

import com.openvie.api.tenant.api.InvitationStatus;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class UserManagementDtos {
    private UserManagementDtos() {}

    @Schema(description = "Workspace member directory plus pending invitations.")
    public record DirectoryResponse(
            List<MemberResponse> members,
            List<InvitationResponse> invitations,
            WorkspaceVisibility visibility,
            boolean defaultWorkspace) {}

    @Schema(description = "One member of a workspace.")
    public record MemberResponse(
            UUID id,
            String email,
            String fullName,
            @Schema(description = "Workspace role: ADMIN or MEMBER.") WorkspaceRole workspaceRole,
            UserStatus status,
            LocalDateTime joinedAt,
            LocalDateTime lastLoginAt,
            @Schema(description = "True when this row is the caller.") boolean currentUser) {}

    @Schema(description = "One pending or settled invitation into a workspace.")
    public record InvitationResponse(
            UUID id,
            String email,
            WorkspaceRole role,
            @Schema(description = "PENDING, EXPIRED, ACCEPTED, or CANCELLED.") InvitationStatus status,
            LocalDateTime invitedAt,
            LocalDateTime expiresAt,
            LocalDateTime lastSentAt) {}

    @Schema(description = "Invite a new member by email into the caller's active workspace.")
    public record InviteRequest(@Schema(description = "Personal or account email; unique inside the workspace.")
                                @NotNull @Email String email,
                                @Schema(description = "Workspace role granted on acceptance: ADMIN or MEMBER.")
                                @NotNull WorkspaceRole role) {}
    @Schema(description = "Role change for one workspace member.")
    public record RoleUpdateRequest(@Schema(description = "New workspace role: ADMIN or MEMBER.") @NotNull WorkspaceRole role) {}
    @Schema(description = "Account status change (owner only): ACTIVE or INACTIVE.")
    public record StatusUpdateRequest(@NotNull UserStatus status) {}

    /** Administrator-set initial password; the member must change it on login. */
    @Schema(description = "Administrator-set initial password; the member must change it on next login.")
    public record PasswordResetRequest(
            @Schema(description = "Initial password (12–128 characters).")
            @NotBlank(message = "Password is required")
            @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
            String password) {}

    @Schema(description = "Add an existing organization account (by email) to the workspace.")
    public record AddMemberToWorkspaceRequest(
            @Schema(description = "Email of an organization account that is not a member of the workspace yet.")
            @NotBlank @Email String email,
            @Schema(description = "Workspace role to grant: ADMIN or MEMBER.")
            @NotNull WorkspaceRole role) {}

    /**
     * Organization owners create a regular account without an email delivery
     * channel. The account must replace its initial password after sign-in.
     */
    @Schema(description = "Owner-created organization account without an email channel; the account must replace "
            + "its initial password after sign-in.")
    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank String fullName,
            @Schema(description = "Initial password (12–128 characters), forced to change on first login.")
            @NotBlank @Size(min = 12, max = 128) String password) {}

    @Schema(description = "One organization account (org-level, workspace-independent).")
    public record OrganizationMemberResponse(
            UUID id,
            String email,
            String fullName,
            OrgRole orgRole,
            UserStatus status,
            LocalDateTime createdAt,
            LocalDateTime lastLoginAt,
            boolean currentUser) {}
}
