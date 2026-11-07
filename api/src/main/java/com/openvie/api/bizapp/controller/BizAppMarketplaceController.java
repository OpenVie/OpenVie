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
import com.openvie.api.bizapp.service.BizAppDraftService;
import com.openvie.api.bizapp.service.BizAppInstallService;
import com.openvie.api.bizapp.service.BizAppMarketplaceService;
import com.openvie.api.common.controller.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;
import java.util.UUID;

/**
 * Marketplace: platform bundles plus this organization's custom templates.
 * Browsing is for every member; publishing ("Save as template"), deletion,
 * install, and uninstall are owner-authority surfaces.
 */
@Tag(name = "BizApp marketplace", description = "Installable Tier-3 app templates")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BizAppMarketplaceController extends BaseController {

    private final BizAppMarketplaceService marketplaceService;
    private final BizAppInstallService installService;
    private final BizAppDraftService draftService;
    @Operation(summary = "Browse the marketplace",
            description = "Platform-bundled templates plus organization custom templates.")
    @GetMapping("/marketplace/bizapps")
    public List<BizAppDtos.TemplateView> catalog(HttpServletRequest request) {
        return marketplaceService.catalog(getOrgId(request));
    }

    @Operation(summary = "Read one template manifest (for remix)")
    @GetMapping("/marketplace/bizapps/{templateRef}")
    public Map<String, Object> template(@PathVariable String templateRef, HttpServletRequest request) {
        return marketplaceService.templateManifest(getOrgId(request), templateRef);
    }

    @Operation(summary = "Save an installed app as an organization template (owner)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Template saved"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may publish templates")
    })
    @PostMapping("/marketplace/bizapps")
    public ResponseEntity<BizAppDtos.TemplateView> saveTemplate(
            @Valid @RequestBody BizAppDtos.SaveTemplateRequest body,
            HttpServletRequest request) {
        requireOrgOwner(request);
        return ResponseEntity.status(201).body(marketplaceService.saveCustom(
                getOrgId(request), getTenantId(request), getUserId(request), body));
    }

    @Operation(summary = "Delete an organization template (owner)")
    @DeleteMapping("/marketplace/bizapps/{templateId}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID templateId, HttpServletRequest request) {
        requireOrgOwner(request);
        marketplaceService.deleteCustom(getOrgId(request), getTenantId(request), getUserId(request), templateId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Install a BizApp into the active workspace (owner)",
            description = "Accepts a `templateRef` (platform:<kind> or a custom template id) or an inline manifest.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Installed; returns the app key + deployed process"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may install apps"),
            @ApiResponse(responseCode = "409", description = "The app kind is already installed or the template is invalid")
    })
    @PostMapping("/apps/install")
    public ResponseEntity<BizAppDtos.InstallResult> install(
            @RequestBody BizAppDtos.InstallRequest body,
            HttpServletRequest request) {
        requireOrgOwner(request);
        BizAppDtos.InstallResult result = installService.install(
                getTenantId(request), getOrgId(request), getUserId(request), resolveManifest(body, request));
        return ResponseEntity.status(201).body(result);
    }

    @Operation(summary = "Upgrade an installed BizApp's manifest in place (owner)",
            description = "Same app kind, higher version, same storage; records are preserved and "
                    + "entity/field removal or type changes are rejected.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upgraded; returns the new version + process"),
            @ApiResponse(responseCode = "403", description = "Only the organization owner may upgrade apps"),
            @ApiResponse(responseCode = "409", description = "The version is not greater than the installed one")
    })
    @PostMapping("/apps/{kind}/upgrade")
    public ResponseEntity<BizAppDtos.InstallResult> upgrade(
            @PathVariable String kind,
            @RequestBody BizAppDtos.InstallRequest body,
            HttpServletRequest request) {
        requireOrgOwner(request);
        BizAppDtos.InstallResult result = installService.upgrade(
                getTenantId(request), getOrgId(request), getUserId(request), kind,
                resolveManifest(body, request));
        return ResponseEntity.ok(result);
    }

    /** Draft, inline manifest, or templateRef — the same resolution for install and upgrade. */
    private Map<String, Object> resolveManifest(BizAppDtos.InstallRequest body, HttpServletRequest request) {
        if (body.draftId() != null) {
            return draftService.getDraft(getOrgId(request), body.draftId()).manifest();
        }
        if (body.manifest() != null && !body.manifest().isEmpty()) {
            return body.manifest();
        }
        return marketplaceService.templateManifest(getOrgId(request), body.templateRef());
    }

    @Operation(summary = "Uninstall an app from the active workspace (owner)",
            description = "Soft operation: records and audit history are retained.")
    @DeleteMapping("/apps/{kind}")
    public ResponseEntity<Void> uninstall(@PathVariable String kind, HttpServletRequest request) {
        requireOrgOwner(request);
        installService.uninstall(getTenantId(request), getUserId(request), kind);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Export template envelope JSON")
    @GetMapping("/marketplace/bizapps/{templateRef}/export")
    public ResponseEntity<Map<String, Object>> exportTemplate(
            @PathVariable String templateRef, HttpServletRequest request) {
        return ResponseEntity.ok(marketplaceService.exportEnvelope(
                getOrgId(request), getTenantId(request), getUserId(request), templateRef));
    }

    @Operation(summary = "Import template envelope JSON as draft (owner)")
    @PostMapping("/marketplace/bizapps/import")
    public ResponseEntity<BizAppDtos.DraftView> importTemplate(
            @Valid @RequestBody BizAppDtos.ImportTemplateRequest body, HttpServletRequest request) {
        requireOrgOwner(request);
        return ResponseEntity.status(201).body(marketplaceService.importTemplate(
                getOrgId(request), getTenantId(request), getUserId(request), body));
    }

    @Operation(summary = "Remix an existing template into a new draft (owner)")
    @PostMapping("/marketplace/bizapps/{templateRef}/remix")
    public ResponseEntity<BizAppDtos.DraftView> remixTemplate(
            @PathVariable String templateRef,
            @RequestBody(required = false) BizAppDtos.RemixRequest body,
            HttpServletRequest request) {
        requireOrgOwner(request);
        return ResponseEntity.status(201).body(marketplaceService.remixTemplate(
                getOrgId(request), getTenantId(request), getUserId(request), templateRef, body));
    }

    @Operation(summary = "Get entries from configured remote catalog")
    @GetMapping("/marketplace/catalog")
    public ResponseEntity<List<BizAppDtos.CatalogEntryView>> remoteCatalog() {
        return ResponseEntity.ok(marketplaceService.remoteCatalog());
    }

    private void requireOrgOwner(HttpServletRequest request) {
        if (!"ORG_OWNER".equals(getOrgRole(request))) {
            throw new AccessDeniedException("Only the organization owner may manage the marketplace");
        }
    }
}
