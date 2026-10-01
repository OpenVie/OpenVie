package com.cacanode.api.tenant.dto;

import com.cacanode.api.tenant.enums.InvitationStatus;
import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

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
            UserRole role,
            UserStatus status,
            LocalDateTime joinedAt,
            LocalDateTime lastLoginAt,
            boolean currentUser) {}

    public record InvitationResponse(
            UUID id,
            String email,
            UserRole role,
            InvitationStatus status,
            LocalDateTime invitedAt,
            LocalDateTime expiresAt,
            LocalDateTime lastSentAt) {}

    public record InviteRequest(@NotNull @Email String email, @NotNull UserRole role) {}
    public record RoleUpdateRequest(@NotNull UserRole role) {}
    public record StatusUpdateRequest(@NotNull UserStatus status) {}
}
