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
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.workflow.api.WorkflowTaskContextApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manifest-driven display context for BizApp approval tasks. The approval
 * inbox must show the same fields the submitter saw, for any installed app,
 * without workflow knowing a business domain.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-TASK-CONTEXT")
public class BizAppTaskContextProvider implements WorkflowTaskContextApi {

    private static final List<String> TITLE_FIELDS = List.of(
            "request_code", "code", "requestCode", "title", "name", "subject", "item_name");
    private static final Set<String> HIDDEN_FIELDS = Set.of(
            "id", "created_at", "updated_at", "created_by", "updated_by");

    private final BizAppInstallationRepository installationRepository;
    private final BizAppRecordRepository recordRepository;
    private final BizAppManifestParser manifestParser;

    @Override
    public TaskContext describe(UUID workspaceId, String appKind, String entity, UUID recordId) {
        if (workspaceId == null || appKind == null || entity == null || recordId == null) {
            return null;
        }
        try {
            BizAppInstallation installation = installationRepository
                    .findByTenantIdAndKindAndStatus(workspaceId, appKind, "ACTIVE")
                    .orElse(null);
            if (installation == null) {
                return null;
            }
            BizAppRecord record = recordRepository
                    .findByIdAndTenantIdAndAppKind(recordId, workspaceId, appKind)
                    .orElse(null);
            if (record == null || !entity.equalsIgnoreCase(record.getEntity())) {
                return null;
            }
            Manifest manifest = manifestParser.parse(installation.getManifest());
            Entity declared = manifest.entities() == null ? null : manifest.entities().stream()
                    .filter(candidate -> entity.equalsIgnoreCase(candidate.name()))
                    .findFirst()
                    .orElse(null);
            if (declared == null || declared.fields() == null || record.getPayload() == null) {
                return null;
            }

            Map<String, String> fields = new LinkedHashMap<>();
            String title = null;
            String titleField = null;
            for (String priority : TITLE_FIELDS) {
                Field match = declared.fields().stream()
                        .filter(field -> priority.equalsIgnoreCase(field.name()))
                        .findFirst().orElse(null);
                if (match != null) {
                    Object value = record.getPayload().get(match.name());
                    if (value != null && !String.valueOf(value).isBlank()) {
                        title = String.valueOf(value);
                        titleField = match.name();
                        break;
                    }
                }
            }
            for (Field field : declared.fields()) {
                if (HIDDEN_FIELDS.contains(field.name())) {
                    continue;
                }
                Object value = record.getPayload().get(field.name());
                if (value == null || String.valueOf(value).isBlank()) {
                    continue;
                }
                fields.put(label(field), String.valueOf(value));
            }
            if (title == null) {
                title = fields.values().stream().findFirst().orElse(humanize(entity));
            }
            List<String> summaryParts = new ArrayList<>();
            for (Field field : declared.fields()) {
                if (field.name().equals(titleField) || HIDDEN_FIELDS.contains(field.name())) {
                    continue;
                }
                Object value = record.getPayload().get(field.name());
                if (value != null && !String.valueOf(value).isBlank()) {
                    summaryParts.add(label(field) + ": " + value);
                }
                if (summaryParts.size() >= 4) {
                    break;
                }
            }
            String summary = summaryParts.isEmpty()
                    ? humanize(appKind) + " · " + humanize(entity)
                    : String.join(" · ", summaryParts);
            return new TaskContext(title, summary, Map.copyOf(fields));
        } catch (Exception exception) {
            log.warn("Could not build task context for app={} record={}: {}",
                    appKind, recordId, exception.getMessage());
            return null;
        }
    }

    private static String label(Field field) {
        Localized label = field.label();
        if (label == null) {
            return humanize(field.name());
        }
        if (label.vi() != null && !label.vi().isBlank()) {
            return label.vi();
        }
        if (label.en() != null && !label.en().isBlank()) {
            return label.en();
        }
        return humanize(field.name());
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "Record";
        }
        return value.replace('_', ' ').replace('-', ' ');
    }
}
