package com.cacanode.api.tenant.controller;

import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.service.WorkspaceService;
import com.cacanode.api.tenant.service.WorkspaceService.MemberView;
import com.cacanode.api.tenant.service.WorkspaceService.OrganizationSnapshot;
import com.cacanode.api.tenant.service.WorkspaceService.WorkspaceView;

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
 */
@Tag(name = "Workspaces", description = "Organization workspace lifecycle (owner only)")
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
public class WorkspaceController extends BaseController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public List<WorkspaceView> list(HttpServletRequest request) {
        return workspaceService.listAll(getUserId(request));
    }

    @PostMapping
    public ResponseEntity<WorkspaceView> create(
            @Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        WorkspaceView created = workspaceService.create(
                getUserId(request), body.getName(), body.getVisibility());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceView rename(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody RenameWorkspaceRequest body,
            HttpServletRequest request) {
        return workspaceService.rename(getUserId(request), workspaceId, body.getName());
    }

    @PatchMapping("/{workspaceId}/visibility")
    public WorkspaceView setVisibility(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody VisibilityRequest body,
            HttpServletRequest request) {
        return workspaceService.setVisibility(getUserId(request), workspaceId, body.getVisibility());
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID workspaceId, HttpServletRequest request) {
        workspaceService.archive(getUserId(request), workspaceId);
    }

    @GetMapping("/{workspaceId}/members")
    public List<MemberView> members(@PathVariable UUID workspaceId, HttpServletRequest request) {
        return workspaceService.members(getUserId(request), workspaceId);
    }

    @PostMapping("/{workspaceId}/members")
    public ResponseEntity<MemberView> addMember(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody AddMemberRequest body,
            HttpServletRequest request) {
        MemberView added = workspaceService.addMember(
                getUserId(request), workspaceId, body.getEmail(), body.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(added);
    }

    @PatchMapping("/{workspaceId}/members/{userId}/role")
    public MemberView changeMemberRole(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @Valid @RequestBody RoleRequest body,
            HttpServletRequest request) {
        return workspaceService.changeMemberRole(
                getUserId(request), workspaceId, userId, body.getRole());
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable UUID workspaceId, @PathVariable UUID userId, HttpServletRequest request) {
        workspaceService.removeMember(getUserId(request), workspaceId, userId);
    }

    // ─── Organization settings ────────────────────────────────────────────────

    @GetMapping("/organization")
    public OrganizationSnapshot organization(HttpServletRequest request) {
        return workspaceService.requireOrg(getOrgId(request));
    }

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
