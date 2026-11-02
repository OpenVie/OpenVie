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
 * Runs one declared manifest report view. SQL is SELECT-only by validator
 * contract; binds are limited to {@code :tenant}, {@code :app}, and any named
 * params the view declared in its manifest.
 */
@Service
@RequiredArgsConstructor
public class BizAppReportResolver {

    private final BizAppInstallService installService;
    private final BizAppManifestParser parser;
    private final BizAppRecordViewQuery query;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> run(UUID tenantId, String appKind, String viewName,
            Map<String, Object> params) {
        var installation = installService.getInstalled(tenantId, appKind);
        var manifest = parser.parse(installation.manifest());
        var block = manifest.blocks() == null ? null : manifest.blocks().stream()
                .filter(candidate -> "report".equals(candidate.type()))
                .filter(candidate -> candidate.params() != null
                        && candidate.params().get("views") instanceof List<?> views
                        && views.stream().anyMatch(view -> view instanceof Map<?, ?> row
                                && viewName.equals(row.get("name"))))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("View " + viewName + " not found"));
        for (Object view : (List<?>) block.params().get("views")) {
            if (view instanceof Map<?, ?> row && viewName.equals(row.get("name"))) {
                return query.run(tenantId, appKind, String.valueOf(row.get("sql")), safeParams(params));
            }
        }
        throw new BadRequestException("View " + viewName + " not found");
    }

    private Map<String, Object> safeParams(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }
        params.forEach((key, value) -> {
            if (key != null && (key.equals("tenant") || key.equals("app"))) {
                throw new BadRequestException("Bind param " + key + " is system-reserved");
            }
        });
        return params;
    }
}
