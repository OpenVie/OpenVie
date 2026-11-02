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

package com.openvie.api.bizapp.service;

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.query.BizAppReportCompiler;
import com.openvie.api.bizapp.query.BizAppRecordViewQuery;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves and executes a declared manifest report view via BizAppReportCompiler.
 */
@Service
@RequiredArgsConstructor
public class BizAppReportResolver {

    private final BizAppInstallService installService;
    private final BizAppManifestParser parser;
    private final BizAppReportCompiler compiler;
    private final BizAppRecordViewQuery query;
    private final com.openvie.api.document.api.AnalyticsRuntimeApi analyticsRuntimeApi;
    private final com.openvie.api.bizapp.service.storage.BizAppStorageRouter storageRouter;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> run(UUID tenantId, String appKind, String viewName,
            Map<String, Object> params) {
        var installation = installService.getInstallationEntity(tenantId, appKind);
        var manifest = parser.parse(installation.getManifest());
        var block = manifest.blocks() == null ? null : manifest.blocks().stream()
                .filter(candidate -> "report".equals(candidate.type()))
                .filter(candidate -> candidate.params() != null
                        && candidate.params().get("views") instanceof List<?> views
                        && views.stream().anyMatch(view -> view instanceof Map<?, ?> row
                                && viewName.equals(row.get("name"))))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("View " + viewName + " not found"));

        for (Object viewObj : (List<?>) block.params().get("views")) {
            if (viewObj instanceof Map<?, ?> row && viewName.equals(row.get("name"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> viewSpec = (Map<String, Object>) row;
                String entityName = String.valueOf(viewSpec.get("entity"));
                Entity entityDef = manifest.entities() == null ? null : manifest.entities().stream()
                        .filter(e -> entityName.equals(e.name()))
                        .findFirst()
                        .orElseThrow(() -> new BadRequestException("Entity " + entityName + " not found"));

                boolean isTyped = storageRouter.isTyped(appKind, entityName);
                String typedTableName = storageRouter.getTableName(appKind, entityName);

                boolean isLakehouse = "lakehouse".equalsIgnoreCase(String.valueOf(viewSpec.get("engine")));
                var compiled = compiler.compile(
                        isLakehouse ? BizAppReportCompiler.Dialect.DUCKDB : BizAppReportCompiler.Dialect.POSTGRES,
                        installation.getOrgId(),
                        tenantId,
                        appKind,
                        entityDef,
                        viewSpec,
                        safeParams(params),
                        isTyped,
                        typedTableName
                );

                if (isLakehouse) {
                    return analyticsRuntimeApi.queryLakehouse(tenantId, "bizapp_records", compiled.sql(), compiled.params());
                }
                return query.run(compiled.sql(), compiled.params());
            }
        }
        throw new BadRequestException("View " + viewName + " not found");
    }

    private Map<String, Object> safeParams(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }
        params.forEach((key, value) -> {
            if (key != null && (key.equals("orgId") || key.equals("tenantId") || key.equals("appKind") || key.equals("entityName"))) {
                throw new BadRequestException("Bind param " + key + " is system-reserved");
            }
        });
        return params;
    }
}
