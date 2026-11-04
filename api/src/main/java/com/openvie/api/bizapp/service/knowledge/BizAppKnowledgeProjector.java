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

package com.openvie.api.bizapp.service.knowledge;

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.service.BizAppReportResolver;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentKind;
import com.openvie.api.tenant.api.TenantKnowledgeApi;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Projects BizApp records and aggregate report views into synthetic Markdown documents
 * stored in the application's workspace knowledge base (Workstream D).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Only entities with {@code knowledge.project: true} are projected.</li>
 *   <li>Fail-closed access rule: projection is skipped unless {@code rbac.read} covers all workspace roles.</li>
 *   <li>Fields with {@code pii: true} and credentials are NEVER projected.</li>
 *   <li>Asynchronous and non-blocking: projection errors never roll back record transactions.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-PROJECTION")
public class BizAppKnowledgeProjector {

    private static final Set<String> ALL_WORKSPACE_ROLES = Set.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER");

    private final DocumentApi documentApi;
    private final TenantKnowledgeApi tenantKnowledgeApi;
    private final BizAppStorageRouter storageRouter;
    private final BizAppReportResolver reportResolver;

    @Setter
    @Value("${app.bizapps.projection-debounce-ms:30000}")
    private long debounceMs = 30000;

    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastDigestRefresh = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingRefreshes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();

    @Async
    public void projectRecord(
            UUID tenantId,
            UUID actorId,
            BizAppInstallation installation,
            Manifest manifest,
            BizAppRecord record) {

        if (manifest == null || manifest.entities() == null || record == null) {
            return;
        }

        Entity entityDef = manifest.entities().stream()
                .filter(e -> record.getEntity().equals(e.name()))
                .findFirst()
                .orElse(null);

        if (entityDef == null || entityDef.knowledge() == null || !Boolean.TRUE.equals(entityDef.knowledge().project())) {
            return;
        }

        // Fail-closed access check: rbac.read must contain all workspace roles
        Map<String, List<String>> rbac = manifest.rbac();
        List<String> readRoles = rbac == null ? null : rbac.get("read");
        if (readRoles == null || !readRoles.containsAll(ALL_WORKSPACE_ROLES)) {
            log.warn("Knowledge projection skipped for record {}: rbac.read does not cover all workspace roles", record.getId());
            return;
        }

        UUID kbId = resolveKnowledgeBaseId(tenantId, installation);
        String fileName = "bizapp__" + record.getAppKind() + "__" + record.getEntity() + "__" + record.getId() + ".md";
        Object lock = fileLocks.computeIfAbsent(tenantId + ":" + kbId + ":" + fileName, k -> new Object());

        synchronized (lock) {
            try {
                if ("ARCHIVED".equalsIgnoreCase(record.getStatus())) {
                    documentApi.deleteByFileName(tenantId, kbId, fileName);
                    updateRecordKnowledgeState(record, "DELETED");
                    return;
                }

                String markdown = buildRecordMarkdown(entityDef, record, manifest);
                documentApi.ingestText(tenantId, actorId, kbId, fileName, markdown, DocumentKind.BIZAPP_RECORD, "bizapp:" + record.getAppKind());
                updateRecordKnowledgeState(record, "PROJECTED");
                log.info("Projected record {} ({}) to knowledge base {}", record.getId(), record.getEntity(), kbId);

            } catch (Exception ex) {
                log.warn("Failed to project record {} to knowledge base: {}", record.getId(), ex.getMessage());
                updateRecordKnowledgeState(record, "FAILED");
            }
        }
    }

    @Async
    public void refreshDigest(
            UUID tenantId,
            UUID actorId,
            BizAppInstallation installation,
            Manifest manifest) {

        if (manifest == null || manifest.blocks() == null) {
            return;
        }

        String debounceKey = tenantId + ":" + installation.getKind();
        long now = System.currentTimeMillis();
        Long last = lastDigestRefresh.get(debounceKey);
        if (debounceMs > 0 && last != null && (now - last) < debounceMs) {
            if (!pendingRefreshes.containsKey(debounceKey)) {
                long delay = debounceMs - (now - last);
                ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
                    pendingRefreshes.remove(debounceKey);
                    doRefreshDigest(tenantId, actorId, installation, manifest);
                }, delay, TimeUnit.MILLISECONDS);
                pendingRefreshes.put(debounceKey, future);
            }
            return;
        }

        doRefreshDigest(tenantId, actorId, installation, manifest);
    }

    public void doRefreshDigest(
            UUID tenantId,
            UUID actorId,
            BizAppInstallation installation,
            Manifest manifest) {

        if (manifest == null || manifest.blocks() == null) {
            return;
        }

        String debounceKey = tenantId + ":" + installation.getKind();
        lastDigestRefresh.put(debounceKey, System.currentTimeMillis());

        UUID kbId = resolveKnowledgeBaseId(tenantId, installation);
        String fileName = "bizapp__" + installation.getKind() + "__digest.md";
        Object lock = fileLocks.computeIfAbsent(tenantId + ":" + kbId + ":" + fileName, k -> new Object());

        synchronized (lock) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(installation.getKind()).append(" Application Digest\n\n");
            sb.append("This document aggregates deterministic report views for the ")
                    .append(installation.getKind()).append(" application.\n\n");

            boolean hasReports = false;
            for (var block : manifest.blocks()) {
                if ("report".equals(block.type()) && block.params() != null && block.params().get("views") instanceof List<?> views) {
                    for (Object viewObj : views) {
                        if (viewObj instanceof Map<?, ?> viewMap) {
                            String viewName = String.valueOf(viewMap.get("name"));
                            try {
                                List<Map<String, Object>> rows = reportResolver.run(tenantId, installation.getKind(), viewName, Map.of());
                                sb.append("## Report: ").append(viewName).append("\n\n");
                                if (rows.isEmpty()) {
                                    sb.append("No active data recorded.\n\n");
                                } else {
                                    List<String> headers = new java.util.ArrayList<>(rows.get(0).keySet());
                                    sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
                                    sb.append("| ").append(String.join(" | ", headers.stream().map(h -> "---").toList())).append(" |\n");
                                    for (Map<String, Object> r : rows) {
                                        sb.append("| ");
                                        for (String h : headers) {
                                            Object val = r.get(h);
                                            sb.append(val != null ? val : "").append(" | ");
                                        }
                                        sb.append("\n");
                                    }
                                    sb.append("\n");
                                }
                                hasReports = true;
                            } catch (Exception ex) {
                                log.debug("Digest report view {} skipped: {}", viewName, ex.getMessage());
                            }
                        }
                    }
                }
            }

            if (hasReports) {
                documentApi.ingestText(tenantId, actorId, kbId, fileName, sb.toString(), DocumentKind.BIZAPP_DIGEST, "bizapp:" + installation.getKind());
                log.info("Refreshed digest document for app {} in knowledge base {}", installation.getKind(), kbId);
            }
        } catch (Exception ex) {
            log.warn("Failed to refresh digest for app {}: {}", installation.getKind(), ex.getMessage());
        }
        }
    }

    private String buildRecordMarkdown(Entity entityDef, BizAppRecord record, Manifest manifest) {
        Map<String, Object> payload = record.getPayload() != null ? record.getPayload() : Map.of();
        String titleField = entityDef.knowledge().titleField();
        String title = titleField != null && payload.get(titleField) != null
                ? String.valueOf(payload.get(titleField))
                : record.getEntity() + " " + record.getId();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("- **ID**: `").append(record.getId()).append("`\n");
        sb.append("- **Entity**: ").append(record.getEntity()).append("\n");
        sb.append("- **App**: ").append(record.getAppKind()).append("\n\n");

        Map<String, Field> fieldDefs = new HashMap<>();
        if (entityDef.fields() != null) {
            entityDef.fields().forEach(f -> fieldDefs.put(f.name(), f));
        }

        List<String> allowedFields = entityDef.knowledge().fields();
        if (allowedFields != null) {
            for (String fieldName : allowedFields) {
                Field f = fieldDefs.get(fieldName);
                if (f != null && Boolean.TRUE.equals(f.pii())) {
                    continue; // PII fields are never projected
                }
                Object value = payload.get(fieldName);
                if (value != null) {
                    sb.append("- **").append(fieldName).append("**: ").append(value).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private void updateRecordKnowledgeState(BizAppRecord record, String state) {
        try {
            Map<String, Object> r = new LinkedHashMap<>(record.getRuntime() == null ? Map.of() : record.getRuntime());
            r.put("knowledge", Map.of("state", state, "updatedAt", Instant.now().toString()));
            record.setRuntime(r);
            storageRouter.route(record.getAppKind(), record.getEntity()).save(record);
        } catch (Exception ex) {
            log.debug("Could not record knowledge projection runtime state: {}", ex.getMessage());
        }
    }

    public UUID resolveKnowledgeBaseId(UUID tenantId, BizAppInstallation installation) {
        Map<String, Object> config = installation.getConfig();
        if (config != null && config.get("knowledgeBaseId") != null) {
            try {
                return UUID.fromString(String.valueOf(config.get("knowledgeBaseId")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return tenantKnowledgeApi.getOrCreateAppKnowledgeBase(tenantId, installation.getKind(), false);
    }
}
