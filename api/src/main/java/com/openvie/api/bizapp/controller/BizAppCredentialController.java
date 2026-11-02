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

package com.openvie.api.bizapp.controller;

import com.openvie.api.bizapp.dto.BizAppDtos;
import com.openvie.api.bizapp.service.BizAppCredentialService;
import com.openvie.api.common.controller.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Credential vault surface for BizApp blocks: owner-authority CRUD where the
 * plaintext exists exactly once (create), listings carry metadata only, and
 * deletes are hard so manifests referencing a dead id fail loudly.
 */
@Tag(name = "BizApp credentials", description = "Organization outbound secrets, AES-256-GCM at rest")
@RestController
@RequestMapping("/api/v1/bizapp-credentials")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BizAppCredentialController extends BaseController {

    private final BizAppCredentialService credentialService;

    @Operation(summary = "List vault entries (owner)", description = "Metadata only — never secrets.")
    @ApiResponse(responseCode = "200", description = "Entries")
    @GetMapping
    public List<BizAppDtos.CredentialView> list(HttpServletRequest request) {
        requireOrgOwner(request);
        return credentialService.list(getOrgId(request));
    }

    @Operation(summary = "Create a vault entry (owner)",
            description = "The plaintext is written only in this request and never echoed back.")
    @ApiResponse(responseCode = "201", description = "Entry created")
    @PostMapping
    public ResponseEntity<BizAppDtos.CredentialView> create(
            @Valid @RequestBody BizAppDtos.CreateCredentialRequest body,
            HttpServletRequest request) {
        requireOrgOwner(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.create(
                getOrgId(request), getTenantId(request), getUserId(request), body));
    }

    @Operation(summary = "Delete a vault entry (owner)")
    @ApiResponse(responseCode = "204", description = "Entry deleted")
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> delete(@PathVariable UUID credentialId, HttpServletRequest request) {
        requireOrgOwner(request);
        credentialService.delete(getOrgId(request), getTenantId(request), getUserId(request), credentialId);
        return ResponseEntity.noContent().build();
    }

    private void requireOrgOwner(HttpServletRequest request) {
        if (!"ORG_OWNER".equals(getOrgRole(request))) {
            throw new AccessDeniedException("Only the organization owner may manage the credential vault");
        }
    }
}
