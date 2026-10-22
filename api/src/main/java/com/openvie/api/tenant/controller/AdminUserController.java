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
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController extends BaseController {

    private final TenantUserManagementService userManagementService;

    @Operation(summary = "List organization accounts",
            description = "All organization accounts with their statuses and default fields; workspace membership inside "
                    + "one workspace is managed by the members surface.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organization members"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may list accounts")
    })
    @GetMapping("/organization")
    public List<OrganizationMemberResponse> organizationMembers(HttpServletRequest request) {
        return userManagementService.getOrganizationMembers(
                getOrgId(request), getTenantId(request), getUserId(request));
    }

    @Operation(summary = "Create an organization account",
            description = "Creates an account in the organization (no membership yet) — the owner-only equivalent of "
                    + "self-registration; the account joins the default workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may create accounts")
    })
    @PostMapping("/organization")
    public ResponseEntity<OrganizationMemberResponse> createOrganizationUser(
            @Valid @RequestBody CreateUserRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                userManagementService.createOrganizationUser(
                        getOrgId(request), getTenantId(request), getUserId(request), body));
    }

    @Operation(summary = "Activate or deactivate an organization account",
            description = "Status must be ACTIVE or INACTIVE; the caller's own account and the organization owner's "
                    + "account cannot be deactivated here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Status must be ACTIVE or INACTIVE, or a protected account was targeted",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found in the organization",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
    @Operation(summary = "Set an organization account password (owner)",
            description = "Owner sets an org account password; the member must change it on next login. "
                    + "Setting the caller's own password is refused — use the self-service change-password endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password set; forced change on next login"),
            @ApiResponse(responseCode = "400", description = "Setting the caller's own password here is refused",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found in the organization",
                    content = @Content(schema = @Schema(implementation = com.openvie.api.common.exception.ErrorResponse.class)))
    })
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
