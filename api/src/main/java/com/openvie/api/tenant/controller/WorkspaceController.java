package com.openvie.api.tenant.controller;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.service.WorkspaceService;
import com.openvie.api.tenant.service.WorkspaceService.MemberView;
import com.openvie.api.tenant.service.WorkspaceService.OrganizationSnapshot;
import com.openvie.api.tenant.service.WorkspaceService.WorkspaceView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-owner workspace lifecycle. Membership inside one workspace is
 * managed by {@link UserController}; these routes reach across workspaces and
 * therefore require the ORG_OWNER authority carried on the principal.
 *
 * <p>Soft admin boundary (Keycloak-ready): the {@code /api/v1/admin/workspaces}
 * alias exposes the same handler under the admin prefix so the gateway can
 * rate-limit and authorize it independently. The canonical path stays until
 * the hard split; the frontend admin surface already calls the alias.
 */
@Tag(name = "Workspaces", description = "Organization workspace lifecycle (owner only)")
@RestController
@RequestMapping({"/api/v1/workspaces", "/api/v1/admin/workspaces"})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
@SecurityRequirement(name = "bearerAuth")
public class WorkspaceController extends BaseController {

    private final WorkspaceService workspaceService;

    @Operation(summary = "List all workspaces of the organization",
            description = "Owner view over every workspace, regardless of the caller's membership.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspaces"),
            @ApiResponse(responseCode = "403", description = "Session is no longer valid, or only the organization owner may manage workspaces")
    })
    @GetMapping
    public List<WorkspaceView> list(HttpServletRequest request) {
        return workspaceService.listAll(getUserId(request));
    }

    @Operation(summary = "Create a workspace",
            description = "Creates a PUBLIC (joinable through self-registration) or PRIVATE workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Workspace created", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Workspace name must be 1-255 characters or visibility is invalid",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may create workspaces")
    })
    @PostMapping
    public ResponseEntity<WorkspaceView> create(
            @Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        WorkspaceView created = workspaceService.create(
                getUserId(request), body.getName(), body.getVisibility());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Rename a workspace")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspace renamed", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Workspace name must be 1-255 characters",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace was not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PatchMapping("/{workspaceId}")
    public WorkspaceView rename(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody RenameWorkspaceRequest body,
            HttpServletRequest request) {
        return workspaceService.rename(getUserId(request), workspaceId, body.getName());
    }

    @Operation(summary = "Change workspace visibility",
            description = "PUBLIC workspaces are joined by self-registered members; the default workspace must stay public.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Visibility applied", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Visibility must be PUBLIC or PRIVATE, or the default workspace must stay public",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace was not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PatchMapping("/{workspaceId}/visibility")
    public WorkspaceView setVisibility(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody VisibilityRequest body,
            HttpServletRequest request) {
        return workspaceService.setVisibility(getUserId(request), workspaceId, body.getVisibility());
    }

    @Operation(summary = "Archive a workspace",
            description = "Archived workspaces refuse new activity; the default workspace cannot be archived.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Workspace archived"),
            @ApiResponse(responseCode = "400", description = "The default workspace cannot be archived",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace was not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID workspaceId, HttpServletRequest request) {
        workspaceService.archive(getUserId(request), workspaceId);
    }

    @Operation(summary = "List workspace members",
            description = "Members of one workspace with their roles; reserved to the owner, even though membership "
                    + "changes inside one workspace are issued from the members surface.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Members"),
            @ApiResponse(responseCode = "404", description = "Workspace was not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @GetMapping("/{workspaceId}/members")
    public List<MemberView> members(@PathVariable UUID workspaceId, HttpServletRequest request) {
        return workspaceService.members(getUserId(request), workspaceId);
    }

    @Operation(summary = "Add an email to the workspace as a member",
            description = "Adds an existing organization account by email to a workspace (org account must already exist).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Member added", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Email or role invalid",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "That account is already a member of this workspace",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace or organization account not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PostMapping("/{workspaceId}/members")
    public ResponseEntity<MemberView> addMember(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody AddMemberRequest body,
            HttpServletRequest request) {
        MemberView added = workspaceService.addMember(
                getUserId(request), workspaceId, body.getEmail(), body.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(added);
    }

    @Operation(summary = "Change a member's workspace role")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "A workspace needs at least one active admin",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace or member not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @PatchMapping("/{workspaceId}/members/{userId}/role")
    public MemberView changeMemberRole(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @Valid @RequestBody RoleRequest body,
            HttpServletRequest request) {
        return workspaceService.changeMemberRole(
                getUserId(request), workspaceId, userId, body.getRole());
    }

    @Operation(summary = "Remove a member from a workspace",
            description = "Removes the membership only; the organization account stays.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Member removed"),
            @ApiResponse(responseCode = "400", description = "You cannot remove yourself, or a workspace needs at least one active admin",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace or member not found",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable UUID workspaceId, @PathVariable UUID userId, HttpServletRequest request) {
        workspaceService.removeMember(getUserId(request), workspaceId, userId);
    }

    // ─── Organization settings ────────────────────────────────────────────────

    @Operation(summary = "Read the organization snapshot",
            description = "Org-level facts (name, default workspace, self-registration state) for the admin panel.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organization snapshot", useReturnTypeSchema = true)
    })
    @GetMapping("/organization")
    public OrganizationSnapshot organization(HttpServletRequest request) {
        return workspaceService.requireOrg(getOrgId(request));
    }

    @Operation(summary = "Toggle organization self-registration",
            description = "Applies or removes the open-registration flag the web setup and login screens honor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Flag applied"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may manage workspaces"),
            @ApiResponse(responseCode = "401", description = "Session is no longer valid")
    })
    @PatchMapping("/organization/self-registration")
    public Map<String, Object> setSelfRegistration(
            @Valid @RequestBody ToggleRequest body, HttpServletRequest request) {
        workspaceService.setSelfRegistration(getUserId(request), body.isAllowed());
        return Map.of("allowSelfRegistration", body.isAllowed());
    }

    @Getter
    @Setter
    public static class CreateWorkspaceRequest {
        @NotBlank(message = "Workspace name is required")
        private String name;
        /** PUBLIC workspaces are joined by self-registered members. */
        private String visibility = "PUBLIC";
    }

    @Getter
    @Setter
    public static class RenameWorkspaceRequest {
        @NotBlank(message = "Workspace name is required")
        private String name;
    }

    @Getter
    @Setter
    public static class VisibilityRequest {
        @NotBlank(message = "Visibility is required")
        private String visibility;
    }

    @Getter
    @Setter
    public static class AddMemberRequest {
        @Email @NotBlank private String email;
        @NotNull private WorkspaceRole role = WorkspaceRole.MEMBER;
    }

    @Getter
    @Setter
    public static class RoleRequest {
        @NotNull private WorkspaceRole role;
    }

    @Getter
    @Setter
    public static class ToggleRequest {
        private boolean allowed;
    }
}
