package com.cacanode.api.tenant.controller;

import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.tenant.dto.UserManagementDtos.DirectoryResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.InvitationResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.InviteRequest;
import com.cacanode.api.tenant.dto.UserManagementDtos.MemberResponse;
import com.cacanode.api.tenant.dto.UserManagementDtos.PasswordResetRequest;
import com.cacanode.api.tenant.dto.UserManagementDtos.RoleUpdateRequest;
import com.cacanode.api.tenant.dto.UserManagementDtos.StatusUpdateRequest;
import com.cacanode.api.tenant.service.TenantUserManagementService;
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

import java.util.UUID;

/**
 * Member directory and invitations for the caller's ACTIVE workspace. Admin
 * authority is enforced twice on purpose: the annotation checks the granted
 * role, and each service call re-resolves membership from the database.
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
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public MemberResponse updateStatus(@PathVariable UUID userId, @Valid @RequestBody StatusUpdateRequest body,
                                       HttpServletRequest request) {
        return userManagementService.updateStatus(getTenantId(request), getUserId(request), userId, body.status());
    }

    /**
     * Administrator sets a member's password; the member must change it on next
     * login. This works with no email configured, which is how a closed
     * workspace (no channel, self-registration off) still onboards people.
     */
    @PutMapping("/{userId}/password")
    @PreAuthorize("hasRole('WORKSPACE_ADMIN')")
    public ResponseEntity<Void> setMemberPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody PasswordResetRequest body,
            HttpServletRequest request) {
        userManagementService.setMemberPassword(
                getTenantId(request), getUserId(request), userId, body.password());
        return ResponseEntity.noContent().build();
    }
}
