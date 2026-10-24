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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

/**
 * Workspace member and invitation management for the caller's active workspace.
 * Mutations resolve live membership again in the service, including the
 * public-workspace rule for regular members.
 */
@Tag(name = "Workspace members", description = "Member directory, invitations, and role management inside the caller's active workspace")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class UserController extends BaseController {
    private final TenantUserManagementService userManagementService;

    /** Add-member autocomplete: organization accounts not yet in the workspace. */
    @Operation(summary = "Suggest organization accounts for add-member",
            description = "Autocomplete for the add-member dialog: organization accounts that are not members of the "
                    + "caller's active workspace yet. Workspace admins only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suggestions", useReturnTypeSchema = true)
    })
    @GetMapping("/suggest")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public List<OrganizationMemberResponse> suggest(
            @RequestParam(name = "query", defaultValue = "") String query,
            HttpServletRequest request) {
        return userManagementService.suggestOrganizationUsers(
                getTenantId(request), getUserId(request), query);
    }

    @Operation(summary = "Read the workspace member directory",
            description = "Members of the caller's active workspace plus the pending invitations an admin can browse.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Members and pending invitations", useReturnTypeSchema = true)
    })
    @GetMapping("/directory")
    public DirectoryResponse directory(HttpServletRequest request) {
        return userManagementService.getDirectory(getTenantId(request), getUserId(request));
    }

    @Operation(summary = "Invite a new member by email",
            description = "Creates (or re-issues) an invitation into the caller's active workspace; the role applies "
                    + "once the invitation is accepted. Workspace admins only.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invitation created or re-sent when one exists", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Email/role validation failed",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only workspace admins may invite")
    })
    @PostMapping("/invitations")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public ResponseEntity<InvitationResponse> invite(@Valid @RequestBody InviteRequest body,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userManagementService.invite(
                getTenantId(request), getUserId(request), body.email(), body.role()));
    }

    @Operation(summary = "Resend an invitation",
            description = "Re-sends the invitation email for a pending or expired invitation, rate-limited per email.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invitation re-sent", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Only pending or expired invitations can be resent, "
                    + "or the resend cooldown has not elapsed",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only workspace admins may resend")
    })
    @PostMapping("/invitations/{invitationId}/resend")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public InvitationResponse resend(@PathVariable UUID invitationId, HttpServletRequest request) {
        return userManagementService.resend(getTenantId(request), getUserId(request), invitationId);
    }

    @Operation(summary = "Cancel a pending invitation")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Invitation cancelled"),
            @ApiResponse(responseCode = "400", description = "Only pending invitations can be cancelled",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only workspace admins may cancel"),
            @ApiResponse(responseCode = "404", description = "Invitation not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public ResponseEntity<Void> cancel(@PathVariable UUID invitationId, HttpServletRequest request) {
        userManagementService.cancel(getTenantId(request), getUserId(request), invitationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Change a member's workspace role",
            description = "Promotes/demotes a member between ADMIN and MEMBER; the caller cannot change their own role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "You cannot change your own role",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only workspace admins may change roles"),
            @ApiResponse(responseCode = "404", description = "Member not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PatchMapping("/{userId}/role")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public MemberResponse updateRole(@PathVariable UUID userId, @Valid @RequestBody RoleUpdateRequest body,
                                     HttpServletRequest request) {
        return userManagementService.updateRole(getTenantId(request), getUserId(request), userId, body.role());
    }

    @Operation(summary = "Activate or deactivate a member account (owner)",
            description = "Account status is an organization-level concern; the owner's own and the organization-owner "
                    + "account cannot be deactivated here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Status must be ACTIVE or INACTIVE, or a protected account was targeted",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may change account status"),
            @ApiResponse(responseCode = "404", description = "Member not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
    @Operation(summary = "Set a member's password (owner)",
            description = "Owner sets a member's password without any email configured — the onboard path for closed "
                    + "workspaces; the member must change it on next login. Setting the caller's own password here is refused.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password set; forced change on next login"),
            @ApiResponse(responseCode = "400", description = "Setting the caller's own password here is refused",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may set passwords"),
            @ApiResponse(responseCode = "404", description = "Member not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
    @Operation(summary = "Add an organization account to the workspace",
            description = "Adds an existing organization account (by email) to the caller's active workspace. "
                    + "Workspace admins only.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Member added", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "409", description = "That account is already a member of this workspace",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Organization account not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
    @Operation(summary = "Remove a member from the active workspace",
            description = "Workspace admin removes one member from their own active workspace. The account itself stays "
                    + "in the organization; the default workspace (General) refuses removal. Deactivating the account "
                    + "is an owner action on the admin surface instead.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Member removed"),
            @ApiResponse(responseCode = "400", description = "The default workspace refuses member removal",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only workspace admins may remove members"),
            @ApiResponse(responseCode = "404", description = "Member not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
    @Operation(summary = "List organization accounts (deprecated)",
            description = "Deprecated — prefer AdminUserController (`/api/v1/admin/users/organization`). Kept for "
                    + "backward compatibility; removal is tracked post-SSO.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organization members"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may list accounts")
    })
    @GetMapping("/organization")
    @PreAuthorize("hasRole('ORG_OWNER')")
    public List<OrganizationMemberResponse> organizationMembers(HttpServletRequest request) {
        return userManagementService.getOrganizationMembers(
                getOrgId(request), getTenantId(request), getUserId(request));
    }

    @Operation(summary = "Create an organization account (deprecated)",
            description = "Deprecated — prefer AdminUserController (`POST /api/v1/admin/users/organization`). Creates an "
                    + "organization account with no workspace membership; the account joins the default workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "That email already belongs to an organization account",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
