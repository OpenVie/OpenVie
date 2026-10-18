package com.openvie.api.tenant.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.openvie.api.common.controller.BaseController;
import com.openvie.api.tenant.dto.UserManagementDtos.CreateUserRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.OrganizationMemberResponse;
import com.openvie.api.tenant.dto.UserManagementDtos.PasswordResetRequest;
import com.openvie.api.tenant.dto.UserManagementDtos.StatusUpdateRequest;
import com.openvie.api.tenant.service.TenantUserManagementService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Organization-owner account administration for the admin panel surface.
 *
 * Workspace membership inside one workspace is managed by {@link UserController};
 * these routes operate on organization accounts regardless of which workspaces
 * they belong to. The workspace scope on each call only anchors the audit trail.
 */
@Tag(name = "Organization admins", description = "Organization account administration (owner only)")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
public class AdminUserController extends BaseController {

    private final TenantUserManagementService userManagementService;

    @GetMapping("/organization")
    public List<OrganizationMemberResponse> organizationMembers(HttpServletRequest request) {
        return userManagementService.getOrganizationMembers(
                getOrgId(request), getTenantId(request), getUserId(request));
    }

    @PostMapping("/organization")
    public ResponseEntity<OrganizationMemberResponse> createOrganizationUser(
            @Valid @RequestBody CreateUserRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                userManagementService.createOrganizationUser(
                        getOrgId(request), getTenantId(request), getUserId(request), body));
    }

    @PatchMapping("/{userId}/status")
    public OrganizationMemberResponse updateStatus(@PathVariable UUID userId,
            @Valid @RequestBody StatusUpdateRequest body,
            HttpServletRequest request) {
        return userManagementService.updateOrgUserStatus(
                getOrgId(request), getTenantId(request), getUserId(request), userId, body.status());
    }

    /**
     * Owner sets an org account password; the member must change it on next
     * login.
     */
    @PutMapping("/{userId}/password")
    public ResponseEntity<Void> setPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody PasswordResetRequest body,
            HttpServletRequest request) {
        userManagementService.setOrgUserPassword(
                getOrgId(request), getTenantId(request), getUserId(request), userId, body.password());
        return ResponseEntity.noContent().build();
    }
}
