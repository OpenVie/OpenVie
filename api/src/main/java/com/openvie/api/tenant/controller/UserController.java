package com.openvie.api.tenant.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.tenant.dto.UserManagementDtos.AddMemberToWorkspaceRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.CreateUserRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.DirectoryResponse;
import com.openvie.api.tenant.dto.UserManagementDtos.InvitationResponse;
import com.openvie.api.tenant.dto.UserManagementDtos.InviteRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.MemberResponse;
import com.openvie.api.tenant.dto.UserManagementDtos.OrganizationMemberResponse;
import com.openvie.api.tenant.dto.UserManagementDtos.PasswordResetRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.RoleUpdateRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.StatusUpdateRequest;
import com.openvie.api.tenant.service.TenantUserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

/**
 * Workspace member and invitation management for the caller's active workspace.
 * Mutations resolve live membership again in the service, including the
 * public-workspace rule for regular members.
 */
@RestController
@RequestMapping({"/api/v1/users", "/api/users"})
@RequiredArgsConstructor
public class UserController extends BaseController {
    private final TenantUserManagementService userManagementService;

    @GetMapping("/directory")
    public DirectoryResponse directory(HttpServletRequest request) {
        return userManagementService.getDirectory(getTenantId(request), getUserId(request));
    }

    @PostMapping("/invitations")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public ResponseEntity<InvitationResponse> invite(@Valid @RequestBody InviteRequest body,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userManagementService.invite(
                getTenantId(request), getUserId(request), body.email(), body.role()));
    }

    @PostMapping("/invitations/{invitationId}/resend")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public InvitationResponse resend(@PathVariable UUID invitationId, HttpServletRequest request) {
        return userManagementService.resend(getTenantId(request), getUserId(request), invitationId);
    }

    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public ResponseEntity<Void> cancel(@PathVariable UUID invitationId, HttpServletRequest request) {
        userManagementService.cancel(getTenantId(request), getUserId(request), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{userId}/role")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public MemberResponse updateRole(@PathVariable UUID userId, @Valid @RequestBody RoleUpdateRequest body,
                                     HttpServletRequest request) {
        return userManagementService.updateRole(getTenantId(request), getUserId(request), userId, body.role());
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public MemberResponse updateStatus(@PathVariable UUID userId, @Valid @RequestBody StatusUpdateRequest body,
                                       HttpServletRequest request) {
        return userManagementService.updateStatus(getTenantId(request), getUserId(request), userId, body.status());
    }

    /**
     * Administrator sets a member's password; the member must change it on next
     * login. This works with no email configured, which is how a closed
     * workspace (no channel, self-registration off) still onboards people.
     *
     * Account status and passwords are organization-level concerns: only the
     * owner reaches this route. Workspace admins remove members from their own
     * workspace instead (see {@code DELETE /users/members/{userId}}); the admin
     * panel uses the org-scoped {@code /api/v1/admin/users} variants.
     */
    @PutMapping("/{userId}/password")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public ResponseEntity<Void> setMemberPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody PasswordResetRequest body,
            HttpServletRequest request) {
        userManagementService.setMemberPassword(
                getTenantId(request), getUserId(request), userId, body.password());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/members")
    public ResponseEntity<MemberResponse> addMember(
            @Valid @RequestBody AddMemberToWorkspaceRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                userManagementService.addMember(
                        getTenantId(request), getUserId(request), body.email(), body.role()));
    }

    /**
     * Workspace admin removes one member from their own active workspace. The
     * account itself stays in the organization; the default workspace (General)
     * refuses removal. Deactivating the account is an owner action on the admin
     * surface instead.
     */
    @DeleteMapping("/members/{userId}")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public ResponseEntity<Void> removeMember(@PathVariable UUID userId,
                                             HttpServletRequest request) {
        userManagementService.removeWorkspaceMember(getTenantId(request), getUserId(request), userId);
        return ResponseEntity.noContent().build();
    }

    // Soft admin boundary note: these two organization endpoints stay on the
    // canonical path in the soft phase because this controller also serves
    // WORKSPACE_ADMIN traffic. In the Keycloak hard split they move to a
    // dedicated admin controller under /api/v1/admin/users/organization.
    // Deprecated: prefer AdminUserController (/api/v1/admin/users/organization).
    // Kept for backward compatibility; removal is tracked post-SSO.
    @GetMapping("/organization")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public List<OrganizationMemberResponse> organizationMembers(HttpServletRequest request) {
        return userManagementService.getOrganizationMembers(
                getOrgId(request), getTenantId(request), getUserId(request));
    }

    @PostMapping("/organization")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public ResponseEntity<OrganizationMemberResponse> createOrganizationUser(
            @Valid @RequestBody CreateUserRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                userManagementService.createOrganizationUser(
                        getOrgId(request), getTenantId(request), getUserId(request), body));
    }
}
