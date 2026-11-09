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

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.dto.BizAppDtos;
import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.service.BizAppInstallService;
import com.openvie.api.bizapp.service.BizAppRecordService;
import com.openvie.api.bizapp.service.BizAppReportResolver;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime surface for installed BizApps: app manifests (rendered by the web
 * client), the generic record data plane, report views, and per-workspace
 * config updates. Write fences come from the manifest RBAC plus the
 * workspace role resolved per request — never trusted from the client.
 */
@Tag(name = "BizApps", description = "Tier-3 business apps installed into a workspace")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BizAppController extends BaseController {

    private final BizAppInstallService installService;
    private final BizAppRecordService recordService;
    private final BizAppReportResolver reportResolver;
    private final BizAppRuntimeApi runtimeApi;
    @Operation(summary = "List installed apps of the active workspace")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Installed apps"))
    @GetMapping("/bizapps")
    public List<BizAppDtos.InstalledAppView> installed(HttpServletRequest request) {
        return installService.listInstalled(getTenantId(request));
    }

    @Operation(summary = "List installed app summary for active workspace (deterministic metrics/catalog)")
    @GetMapping("/bizapps/installed-summary")
    public List<BizAppRuntimeApi.InstalledAppSummary> installedSummary(HttpServletRequest request) {
        return runtimeApi.listInstalledSummary(getTenantId(request));
    }

    @Operation(summary = "Whether the organization has an active app installed in any workspace")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Organization-wide app availability"))
    @GetMapping("/bizapps/installed-anywhere")
    public Map<String, Boolean> installedAnywhere(HttpServletRequest request) {
        return Map.of("installed", runtimeApi.hasAnyInstalledApp(getOrgId(request)));
    }

    @Operation(summary = "One installed app with its frozen manifest")
    @GetMapping("/bizapps/{kind}")
    public BizAppDtos.InstalledAppView installedApp(@PathVariable String kind, HttpServletRequest request) {
        return installService.getInstalled(getTenantId(request), kind);
    }

    @Operation(summary = "Query one manifest report view")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rows"),
            @ApiResponse(responseCode = "403", description = "Role not allowed by the manifest")
    })
    @PostMapping("/bizapps/{kind}/reports/{viewName}")
    public List<Map<String, Object>> report(
            @PathVariable String kind,
            @PathVariable String viewName,
            @RequestBody(required = false) BizAppDtos.ReportRunRequest body,
            HttpServletRequest request) {
        boolean admin = isWorkspaceAdmin(request);
        if (!admin) {
            throw new AccessDeniedException("Report views are workspace-admin material");
        }
        return reportResolver.run(getTenantId(request), kind, viewName,
                body == null ? Map.of() : body.params());
    }

    @Operation(summary = "Replace org-level app configuration (owner)")
    @PatchMapping("/bizapps/{kind}/config")
    public ResponseEntity<BizAppDtos.InstalledAppView> updateConfig(
            @PathVariable String kind,
            @Valid @RequestBody BizAppDtos.UpdateConfigRequest body,
            HttpServletRequest request) {
        requireOrgOwner(request);
        return ResponseEntity.ok(
                installService.updateConfig(getTenantId(request), getUserId(request), kind, body.config()));
    }

    // ------------------------------------------------------------ records

    @Operation(summary = "List records of one app entity")
    @GetMapping("/bizapp-data/{kind}/{entity}")
    public org.springframework.data.domain.Page<RecordView> records(
            @PathVariable String kind, @PathVariable String entity,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return recordService.list(getTenantId(request), kind, entity, includeArchived, page, size,
                getManifestRole(request));
    }

    @Operation(summary = "Create one listing")
    @PostMapping("/bizapp-data/{kind}/{entity}")
    public ResponseEntity<RecordView> createRecord(
            @PathVariable String kind, @PathVariable String entity,
            @Valid @RequestBody BizAppDtos.CreateRecordRequest body,
            HttpServletRequest request) {
        RecordView view = recordService.create(getTenantId(request), getUserId(request),
                getManifestRole(request), kind, entity, body.payload());
        return ResponseEntity.status(201).body(view);
    }

    @Operation(summary = "Get one record")
    @GetMapping("/bizapp-data/{kind}/{entity}/{recordId}")
    public RecordView getRecord(
            @PathVariable String kind, @PathVariable String entity,
            @PathVariable UUID recordId, HttpServletRequest request) {
        return recordService.get(getTenantId(request), kind, entity, recordId, getManifestRole(request));
    }

    @Operation(summary = "Patch one record (unknown fields rejected)")
    @PatchMapping("/bizapp-data/{kind}/{entity}/{recordId}")
    public RecordView patchRecord(
            @PathVariable String kind, @PathVariable String entity, @PathVariable UUID recordId,
            @Valid @RequestBody BizAppDtos.UpdateRecordRequest body, HttpServletRequest request) {
        return recordService.update(getTenantId(request), getUserId(request), getManifestRole(request),
                kind, entity, recordId, body.payload());
    }

    @Operation(summary = "Archive one record")
    @DeleteMapping("/bizapp-data/{kind}/{entity}/{recordId}")
    public ResponseEntity<Void> archiveRecord(
            @PathVariable String kind, @PathVariable String entity, @PathVariable UUID recordId,
            HttpServletRequest request) {
        recordService.archive(getTenantId(request), getUserId(request), getManifestRole(request),
                kind, entity, recordId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Submit one record into the app's approval definition",
            description = "Starts the Flowable process; tasks run through the generic engine inbox.")
    @PostMapping("/bizapp-data/{kind}/{entity}/{recordId}/submit")
    public RecordView submitRecord(
            @PathVariable String kind, @PathVariable String entity, @PathVariable UUID recordId,
            HttpServletRequest request) {
        return recordService.submit(getTenantId(request), getUserId(request), getManifestRole(request),
                kind, entity, recordId);
    }

    private void requireOrgOwner(HttpServletRequest request) {
        if (!"ORG_OWNER".equals(getOrgRole(request))) {
            throw new AccessDeniedException("Only the organization owner may manage app configuration");
        }
    }
}
