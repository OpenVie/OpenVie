package com.cacanode.api.tenant.dto;

import com.cacanode.api.tenant.api.InvitationStatus;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class UserManagementDtos {
    private UserManagementDtos() {}

    public record DirectoryResponse(List<MemberResponse> members, List<InvitationResponse> invitations) {}

    public record MemberResponse(
            UUID id,
            String email,
            String fullName,
            WorkspaceRole workspaceRole,
            UserStatus status,
            LocalDateTime joinedAt,
            LocalDateTime lastLoginAt,
            boolean currentUser) {}

    public record InvitationResponse(
            UUID id,
            String email,
            WorkspaceRole role,
            InvitationStatus status,
            LocalDateTime invitedAt,
            LocalDateTime expiresAt,
            LocalDateTime lastSentAt) {}

    public record InviteRequest(@NotNull @Email String email, @NotNull WorkspaceRole role) {}
    public record RoleUpdateRequest(@NotNull WorkspaceRole role) {}
    public record StatusUpdateRequest(@NotNull UserStatus status) {}

    /** Administrator-set initial password; the member must change it on login. */
    public record PasswordResetRequest(
            @NotBlank(message = "Password is required")
            @Size(min = 12, max = 128, message = "Password must be at least 12 characters")
            String password) {}
}
