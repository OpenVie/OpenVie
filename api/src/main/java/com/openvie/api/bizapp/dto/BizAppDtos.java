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

package com.openvie.api.bizapp.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Wire records of the BizApp surface: marketplace catalog, install lifecycle,
 * runtime rendering, generic record CRUD, and the credential vault.
 */
public final class BizAppDtos {
    private BizAppDtos() {
    }

    @Schema(description = "One marketplace entry (platform-bundled or org-crafted).")
    public record TemplateView(
            UUID id,
            @Schema(description = "`platform` for bundled templates, `org` for custom ones.") String source,
            String kind,
            String name,
            String nameVi,
            String summary,
            int version
    ) {
    }

    @Schema(description = "Save the request body as an organization template.")
    public record SaveTemplateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String summary,
            @NotNull Map<String, Object> manifest
    ) {
    }

    @Schema(description = "Install a BizApp from a template id or an inline manifest.")
    public record InstallRequest(
            @Schema(description = "Template to install; `platform:<kind>` or an organization template UUID.") String templateRef,
            Map<String, Object> manifest
    ) {
    }

    @Schema(description = "One installed app as the mobile/web runtime sees it.")
    public record InstalledAppView(
            String kind,
            int version,
            String nameEn,
            String nameVi,
            String icon,
            String status,
            Map<String, Object> manifest,
            Map<String, Object> config,
            String processKey,
            LocalDateTime installedAt
    ) {
    }

    @Schema(description = "Replace org-level configuration (block bindings such as knowledge-base ids).")
    public record UpdateConfigRequest(Map<String, Object> config) {
    }

    @Schema(description = "Create one manifest-validated record.")
    public record CreateRecordRequest(@NotNull Map<String, Object> payload) {
    }

    @Schema(description = "Patch fields of one record (unknown keys rejected).")
    public record UpdateRecordRequest(@NotNull Map<String, Object> payload) {
    }

    @Schema(description = "Registry view of one record; runtime state is separated from user payload.")
    public record RecordView(
            UUID id,
            String appKind,
            String entity,
            Map<String, Object> payload,
            Map<String, Object> runtime,
            String status,
            UUID createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    @Schema(description = "Report view result: named bind params, one JSON row per array element.")
    public record ReportRunRequest(Map<String, Object> params) {
    }

    @Schema(description = "Save one vault entry; the plaintext exists only in this request.")
    public record CreateCredentialRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Schema(description = "`api_key`, `bearer` or `basic`.") String format,
            @NotBlank @Schema(description = "Secret plaintext; never echoed back.") String secret,
            @Schema(description = "When `basic` carries user:pass as `secret`.") String user
    ) {
    }

    @Schema(description = "Vault entry without secret material.")
    public record CredentialView(UUID id, String name, String format, LocalDateTime createdAt) {
    }

    @Schema(description = "Install result.")
    public record InstallResult(UUID installationId, String kind, int version, String processKey) {
    }
}
