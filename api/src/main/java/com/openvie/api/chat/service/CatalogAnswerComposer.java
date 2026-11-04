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

package com.openvie.api.chat.service;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Composes deterministic, citation-free answers for app listings and details (W1.2).
 * Reads directly from the database and bundled manifests without calling LLM.
 */
@Component
@RequiredArgsConstructor
public class CatalogAnswerComposer {

    private final BizAppRuntimeApi bizAppRuntimeApi;

    public String composeListApps(UUID tenantId, String locale) {
        boolean isVi = isVietnamese(locale);
        List<InstalledAppSummary> apps = bizAppRuntimeApi.listInstalledSummary(tenantId);
        if (apps.isEmpty()) {
            return isVi
                    ? "Hiện tại chưa có ứng dụng nào được cài đặt trong không gian làm việc này."
                    : "No applications are currently installed in this workspace.";
        }

        StringBuilder sb = new StringBuilder();
        if (isVi) {
            sb.append("### Danh sách ứng dụng đã cài đặt (").append(apps.size()).append("):\n\n");
        } else {
            sb.append("### Installed Applications (").append(apps.size()).append("):\n\n");
        }

        for (InstalledAppSummary app : apps) {
            sb.append("- **").append(app.name()).append("** (`").append(app.kind()).append("`, v").append(app.version()).append(")\n");
            if (app.description() != null && !app.description().isBlank()) {
                sb.append("  ").append(app.description()).append("\n");
            }
            if (app.entities() != null && !app.entities().isEmpty()) {
                sb.append("  - ").append(isVi ? "Thực thể: " : "Entities: ")
                        .append(String.join(", ", app.entities())).append("\n");
            }
            if (app.views() != null && !app.views().isEmpty()) {
                sb.append("  - ").append(isVi ? "Giao diện: " : "Views: ")
                        .append(String.join(", ", app.views())).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    public String composeAppDetail(UUID tenantId, String appKind, String locale) {
        boolean isVi = isVietnamese(locale);
        if (appKind == null || appKind.isBlank()) {
            return isVi ? "Vui lòng chỉ định tên ứng dụng cần xem chi tiết." : "Please specify an application to inspect.";
        }

        List<InstalledAppSummary> installed = bizAppRuntimeApi.listInstalledSummary(tenantId);
        var installedOpt = installed.stream().filter(a -> a.kind().equalsIgnoreCase(appKind)).findFirst();

        if (installedOpt.isPresent()) {
            InstalledAppSummary app = installedOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("### ").append(app.name()).append(" (`").append(app.kind()).append("`)\n\n");
            sb.append("- **").append(isVi ? "Trạng thái" : "Status").append("**: `").append(app.status()).append("`\n");
            sb.append("- **").append(isVi ? "Phiên bản" : "Version").append("**: ").append(app.version()).append("\n");
            if (app.description() != null && !app.description().isBlank()) {
                sb.append("- **").append(isVi ? "Mô tả" : "Description").append("**: ").append(app.description()).append("\n");
            }
            if (app.entities() != null && !app.entities().isEmpty()) {
                sb.append("- **").append(isVi ? "Thực thể" : "Entities").append("**: ")
                        .append(String.join(", ", app.entities())).append("\n");
            }
            if (app.views() != null && !app.views().isEmpty()) {
                sb.append("- **").append(isVi ? "Giao diện" : "Views").append("**: ")
                        .append(String.join(", ", app.views())).append("\n");
            }
            return sb.toString().trim();
        }

        List<MarketplaceTemplateSummary> templates = bizAppRuntimeApi.listMarketplaceSummary(tenantId);
        var templateOpt = templates.stream().filter(t -> t.kind().equalsIgnoreCase(appKind)).findFirst();
        if (templateOpt.isPresent()) {
            MarketplaceTemplateSummary tmpl = templateOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("### ").append(tmpl.name()).append(" (`").append(tmpl.kind()).append("`)\n\n");
            sb.append("- **").append(isVi ? "Trạng thái" : "Status").append("**: `").append(isVi ? "Chưa cài đặt" : "Available in catalog").append("`\n");
            sb.append("- **").append(isVi ? "Phiên bản" : "Version").append("**: ").append(tmpl.version()).append("\n");
            if (tmpl.description() != null && !tmpl.description().isBlank()) {
                sb.append("- **").append(isVi ? "Mô tả" : "Description").append("**: ").append(tmpl.description()).append("\n");
            }
            return sb.toString().trim();
        }

        return isVi
                ? "Không tìm thấy thông tin cho ứng dụng: " + appKind
                : "Application details not found for: " + appKind;
    }

    private boolean isVietnamese(String locale) {
        if (locale == null) {
            return false;
        }
        String lower = locale.toLowerCase(Locale.ROOT);
        return lower.startsWith("vi");
    }
}
