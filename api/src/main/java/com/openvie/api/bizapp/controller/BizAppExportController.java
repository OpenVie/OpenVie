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
import com.openvie.api.bizapp.service.BizAppExportService;
import com.openvie.api.bizapp.service.BizAppExportService.ExportFile;
import com.openvie.api.bizapp.service.BizAppExportService.ExportFormat;
import com.openvie.api.common.controller.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Download surface for BizApp data: record registries and report views as CSV
 * or XLSX. Scoping and permissions are the same as the screens they mirror —
 * records through the manifest RBAC fence, reports through the workspace-admin
 * fence.
 */
@Tag(name = "BizApps", description = "Tier-3 business apps installed into a workspace")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BizAppExportController extends BaseController {

    private final BizAppExportService exportService;

    @Operation(summary = "Export one app entity as CSV or XLSX")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File attachment"),
            @ApiResponse(responseCode = "400", description = "Unknown format/entity or over the row cap"),
            @ApiResponse(responseCode = "403", description = "Role not allowed by the manifest")
    })
    @GetMapping("/bizapps/{kind}/entities/{entity}/export")
    public ResponseEntity<byte[]> exportRecords(
            @PathVariable String kind,
            @PathVariable String entity,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            HttpServletRequest request) {
        ExportFile file = exportService.exportRecords(
                getTenantId(request), kind, entity, getManifestRole(request),
                includeArchived, ExportFormat.parse(format), normalizeLocale(locale));
        return attachment(file);
    }

    @Operation(summary = "Export one manifest report view as CSV or XLSX")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File attachment"),
            @ApiResponse(responseCode = "400", description = "Unknown format or view"),
            @ApiResponse(responseCode = "403", description = "Caller is not a workspace admin")
    })
    @PostMapping("/bizapps/{kind}/reports/{viewName}/export")
    public ResponseEntity<byte[]> exportReport(
            @PathVariable String kind,
            @PathVariable String viewName,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "en") String locale,
            @RequestBody(required = false) BizAppDtos.ReportRunRequest body,
            HttpServletRequest request) {
        if (!isWorkspaceAdmin(request)) {
            throw new AccessDeniedException("Report views are workspace-admin material");
        }
        ExportFile file = exportService.exportReport(
                getTenantId(request), kind, viewName, ExportFormat.parse(format),
                normalizeLocale(locale), body == null ? null : body.params());
        return attachment(file);
    }

    /** Anything but an explicit Vietnamese request exports English headers. */
    private String normalizeLocale(String locale) {
        return "vi".equalsIgnoreCase(locale) ? "vi" : "en";
    }

    /**
     * Attachment response carrying both the ASCII fallback and the RFC 5987
     * UTF-8 name, so browsers show the real filename while old clients still
     * get a usable one.
     */
    private ResponseEntity<byte[]> attachment(ExportFile file) {
        String asciiName = file.filename().replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        String encoded = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.content().length))
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
