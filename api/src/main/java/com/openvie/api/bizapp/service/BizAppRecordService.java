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

import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.bizapp.service.block.ApprovalWorkItemBlock;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor.BlockContext;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor.BlockResult;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor.BlockSpec;
import com.openvie.api.bizapp.service.block.BizAppBlockRegistry;
import com.openvie.api.bizapp.service.block.WebhookOutBlock;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The BizApp record data plane: manifest-validated CRUD per installed app,
 * role fences from the manifest, lifecycle hooks, and the approval bridge to
 * the Flowable engine. Cross-tenant isolation is structural — every lookup is
 * keyed by the caller's tenant id.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP")
public class BizAppRecordService {

    private final BizAppRecordRepository recordRepository;
    private final BizAppInstallationRepository installationRepository;
    private final BizAppManifestParser manifestParser;
    private final BizAppPayloadValidator payloadValidator;
    private final BizAppBlockRegistry blockRegistry;
    private final BizAppRuntime runtime;
    private final WebhookOutBlock webhookOutBlock;
    private final com.openvie.api.bizapp.service.storage.BizAppStorageRouter storageRouter;
    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public RecordView get(UUID tenantId, String appKind, UUID recordId) {
        installationOf(tenantId, appKind);
        return toView(resolve(tenantId, appKind, recordId),
                manifestParser.parse(installationOf(tenantId, appKind).getManifest()));
    }

    @Transactional(readOnly = true)
    public Page<RecordView> list(UUID tenantId, String appKind, String entity, boolean includeArchived,
            int page, int size) {
        installationOf(tenantId, appKind);
        ensureEntity(installationOf(tenantId, appKind), appKind, entity);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<BizAppRecord> rows = storageRouter.route(appKind, entity)
                .list(tenantId, appKind, entity, includeArchived, PageRequest.of(Math.max(page, 0), safeSize));
        Manifest manifest = manifestParser.parse(installationOf(tenantId, appKind).getManifest());
        return rows.map(row -> toView(row, manifest));
    }

    // ----------------------------------------------------------------- write

    @Transactional
    public RecordView create(UUID tenantId, UUID userId, boolean admin, String appKind,
            String entity, Map<String, Object> payload) {
        BizAppInstallation installation = activeInstallationForWrite(tenantId, appKind, admin, entity);
        Manifest manifest = manifestParser.parse(installation.getManifest());

        Map<String, Object> clean = payloadValidator.sanitize(manifest, entity, payload, true);
        BizAppRecord record = new BizAppRecord();
        record.setOrgId(installation.getOrgId());
        record.setTenantId(tenantId);
        record.setAppKind(appKind);
        record.setEntity(entity);
        record.setPayload(clean);
        record.setStatus("ACTIVE");
        record.setCreatedBy(userId);
        record.setRuntime(Map.of());
        BizAppRecord saved = storageRouter.route(appKind, entity).save(record);

        runtime.audit(tenantId, userId, LogAction.BIZAPP_RECORD_CREATED, "bizapp_record", saved.getId(),
                Map.of("app", appKind, "entity", entity));
        runHooks(manifest, installation, saved, "onCreate", userId);
        lifecycleWebhook(tenantId, installation, manifest, saved, "record.created",
                Map.of("created_by", userId.toString()));
        return toView(saved, manifest);
    }

    @Transactional
    public RecordView update(UUID tenantId, UUID userId, boolean admin, String appKind,
            String entity, UUID recordId, Map<String, Object> payload) {
        BizAppInstallation installation = activeInstallationForWrite(tenantId, appKind, admin, entity);
        BizAppRecord record = resolveForWrite(tenantId, appKind, entity, recordId);
        Manifest manifest = manifestParser.parse(installation.getManifest());
        payload.keySet().forEach(key -> {
            if (manifest.entities().stream().noneMatch(candidate -> candidate.name().equals(entity))
                    || unknownField(manifest, entity, key)) {
                throw new BadRequestException("Unknown payload field " + key);
            }
        });
        Map<String, Object> clean = payloadValidator.sanitize(manifest, entity, payload, false);
        Map<String, Object> merged = new LinkedHashMap<>(record.getPayload() == null ? Map.of() : record.getPayload());
        merged.putAll(clean);
        record.setPayload(merged);
        record.setRuntime(withDecisionReset(record));
        record.setUpdatedBy(userId);
        BizAppRecord saved = storageRouter.route(appKind, entity).save(record);

        runtime.audit(tenantId, userId, LogAction.BIZAPP_RECORD_UPDATED, "bizapp_record", saved.getId(),
                Map.of("app", appKind, "entity", entity, "fields", clean.keySet()));
        runHooks(manifest, installation, saved, "onUpdate", userId);
        lifecycleWebhook(tenantId, installation, manifest, saved, "record.updated",
                Map.of("updated_by", userId.toString(), "fields", clean.keySet()));
        return toView(saved, manifest);
    }

    @Transactional
    public RecordView archive(UUID tenantId, UUID userId, boolean admin, String appKind,
            String entity, UUID recordId) {
        BizAppInstallation installation = activeInstallationForWrite(tenantId, appKind, admin, entity);
        BizAppRecord record = resolveForWrite(tenantId, appKind, entity, recordId);
        record.setStatus("ARCHIVED");
        record.setUpdatedBy(userId);
        BizAppRecord saved = storageRouter.route(appKind, entity).save(record);
        runtime.audit(tenantId, userId, LogAction.BIZAPP_RECORD_ARCHIVED, "bizapp_record", recordId,
                Map.of("app", appKind, "entity", entity));
        return toView(saved, manifestParser.parse(installation.getManifest()));
    }

    // --------------------------------------------------------------- approve

    /**
     * Submit the record into its app's approval definition. The generic engine
     * task inbox claims/processes; the terminal delegate writes the decision
     * and raises {@code bizapp.<kind>.record.decided}.
     */
    @Transactional
    public RecordView submit(UUID tenantId, UUID userId, boolean admin, String appKind,
            String entity, UUID recordId) {
        BizAppInstallation installation = activeInstallationForWrite(tenantId, appKind, admin, entity);
        BizAppRecord record = resolveForWrite(tenantId, appKind, entity, recordId);
        Manifest manifest = manifestParser.parse(installation.getManifest());
        Optional<Block> approval = manifest.blocks() == null ? Optional.empty()
                : manifest.blocks().stream()
                        .filter(block -> ApprovalWorkItemBlock.TYPE.equals(block.type()))
                        .findFirst();
        if (approval.isEmpty()) {
            throw new BadRequestException("This app has no approval_workitem block; nothing to submit");
        }
        if (record.getRuntime() != null && record.getRuntime().get("processId") != null
                && "PENDING".equals(record.getRuntime().get("approvalStatus"))) {
            throw new ConflictException("This record is already pending approval");
        }
        approval.ifPresent(block -> {
            BlockSpec spec = new BlockSpec(block.id(), block.params());
            BlockContext context = contextFor(installation, manifest, record, userId);
            BlockResult result = blockRegistry.executor(ApprovalWorkItemBlock.TYPE).orElseThrow()
                    .execute(spec, context);
            Map<String, Object> runtimeState = new LinkedHashMap<>(
                    record.getRuntime() == null ? Map.of() : record.getRuntime());
            runtimeState.put("processId", result.summary());
            runtimeState.put("approvalStatus", "PENDING");
            record.setRuntime(runtimeState);
        });
        record.setUpdatedBy(userId);
        BizAppRecord saved = storageRouter.route(appKind, entity).save(record);
        runtime.audit(tenantId, userId, LogAction.BIZAPP_RECORD_SUBMITTED, "bizapp_record", recordId,
                Map.of("app", appKind, "entity", entity, "process", installation.getProcessKey()));
        lifecycleWebhook(tenantId, installation, manifest, saved, "record.submitted",
                Map.of("submitted_by", userId.toString()));
        return toView(saved, manifest);
    }

    /**
     * Called by {@code BizAppRecordDecisionDelegate} inside the process
     * transaction: persists the terminal decision and raises the decided
     * webhook. Engine-provided values; tenant scoping comes from the process
     * variables already verified at start.
     */
    @Transactional
    public void applyEngineDecision(UUID recordId, String appKind,
            Map<String, Object> decisionState, Map<String, Object> eventData) {
        BizAppRecord record = findAny(recordId, appKind);
        if (record == null) {
            log.warn("Engine decision for missing/foreign record {} (app {})", recordId, appKind);
            return;
        }
        Map<String, Object> runtimeState = new LinkedHashMap<>(record.getRuntime() == null ? Map.of() : record.getRuntime());
        runtimeState.put("processId", decisionState.get("processId"));
        runtimeState.put("approvalStatus", "APPROVE".equals(decisionState.get("decision")) ? "APPROVED" : "REJECTED");
        runtimeState.put("decision", decisionState);
        record.setRuntime(runtimeState);
        storageRouter.route(record.getAppKind(), record.getEntity()).save(record);

        runtime.audit(record.getTenantId(), record.getCreatedBy(), LogAction.BIZAPP_RECORD_DECIDED,
                "bizapp_record", recordId, Map.of("app", appKind, "decision", decisionState.get("decision")));
        runtime.lifecycle(record.getOrgId(), record.getTenantId(),
                "bizapp." + appKind + ".record.decided", recordId, eventData);
    }

    // --------------------------------------------------------------- helpers

    private boolean unknownField(Manifest manifest, String entityName, String key) {
        return manifest.entities().stream()
                .filter(entity -> entityName.equals(entity.name()))
                .findFirst()
                .map(entity -> entity.fields() == null || entity.fields().stream()
                        .noneMatch(field -> key.equals(field.name())))
                .orElse(true);
    }

    private Map<String, Object> withDecisionReset(BizAppRecord record) {
        Map<String, Object> runtime = new LinkedHashMap<>(record.getRuntime() == null ? Map.of() : record.getRuntime());
        runtime.remove("processId");
        runtime.remove("approvalStatus");
        runtime.remove("decision");
        return runtime;
    }

    private void runHooks(Manifest manifest, BizAppInstallation installation, BizAppRecord record,
            String hookKey, UUID actorId) {
        Entity entity = manifest.entities().stream()
                .filter(candidate -> candidate.name().equals(record.getEntity()))
                .findFirst()
                .orElse(null);
        List<String> targets = entity == null || entity.hooks() == null
                ? List.of() : entity.hooks().getOrDefault(hookKey, List.of());
        for (String target : targets) {
            try {
                if (target.startsWith("webhook_out:")) {
                    String eventKind = target.substring("webhook_out:".length());
                    webhookOutBlock.publish(installation.getKind(), eventKind,
                            contextFor(installation, manifest, record, actorId), null);
                } else {
                    manifest.block(target).ifPresent(block -> {
                        if ("approval_workitem".equals(block.type())) {
                            return;
                        }
                        blockRegistry.executor(block.type()).ifPresent(executor -> {
                            var result = executor.execute(new BlockSpec(block.id(), block.params()),
                                    contextFor(installation, manifest, record, actorId));
                            if (result != null && result.runtimeUpdates() != null && !result.runtimeUpdates().isEmpty()) {
                                Map<String, Object> r = new LinkedHashMap<>(record.getRuntime() == null ? Map.of() : record.getRuntime());
                                r.putAll(result.runtimeUpdates());
                                record.setRuntime(r);
                                storageRouter.route(record.getAppKind(), record.getEntity()).save(record);
                            }
                        });
                    });
                }
            } catch (Exception exception) {
                log.warn("Hook {} target {} failed for record {}: {}", hookKey, target,
                        record.getId(), exception.getMessage());
            }
        }
    }

    /** Decision + submit integrations always push; per-event hooks stay opt-in. */
    private void lifecycleWebhook(UUID tenantId, BizAppInstallation installation, Manifest manifest,
            BizAppRecord record, String eventSuffix, Map<String, Object> extra) {
        boolean hasWebhooks = manifest.blocks() != null && manifest.blocks().stream()
                .anyMatch(block -> WebhookOutBlock.TYPE.equals(block.type()));
        if (hasWebhooks) {
            runtime.lifecycle(installation.getOrgId(), tenantId,
                    "bizapp." + installation.getKind() + "." + eventSuffix, record.getId(), extra);
        }
    }

    private BlockContext contextFor(BizAppInstallation installation, Manifest manifest,
            BizAppRecord record, UUID actorId) {
        return new BlockContext(
                installation.getOrgId(),
                installation.getTenantId(),
                actorId,
                record.getAppKind(),
                record.getEntity(),
                record.getId(),
                record.getPayload(),
                installation.getProcessKey(),
                installation.getConfig() != null ? installation.getConfig() : Map.of(),
                installation.getVersion());
    }

    private BizAppRecord resolve(UUID tenantId, String appKind, UUID recordId) {
        var installation = installationOf(tenantId, appKind);
        Manifest manifest = manifestParser.parse(installation.getManifest());
        if (manifest.entities() != null) {
            for (var e : manifest.entities()) {
                if (storageRouter.isTyped(appKind, e.name())) {
                    var found = storageRouter.route(appKind, e.name()).findById(tenantId, appKind, recordId);
                    if (found.isPresent()) {
                        return found.get();
                    }
                }
            }
        }
        return storageRouter.route(appKind, "").findById(tenantId, appKind, recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record not found in this app"));
    }

    private BizAppRecord resolveForWrite(UUID tenantId, String appKind, String entity, UUID recordId) {
        return storageRouter.route(appKind, entity).findById(tenantId, appKind, recordId)
                .filter(record -> entity.equals(record.getEntity()))
                .orElseThrow(() -> new ResourceNotFoundException("Record not found in this app"));
    }

    private BizAppRecord findAny(UUID recordId, String appKind) {
        if ("crm".equalsIgnoreCase(appKind)) {
            var crmLead = storageRouter.route("crm", "leads").findById(null, "crm", recordId);
            if (crmLead.isPresent()) return crmLead.get();
        }
        if ("booking".equalsIgnoreCase(appKind)) {
            var bAppt = storageRouter.route("booking", "bookings").findById(null, "booking", recordId);
            if (bAppt.isPresent()) return bAppt.get();
        }
        return recordRepository.findById(recordId)
                .filter(row -> appKind.equals(row.getAppKind()) && "ACTIVE".equals(row.getStatus()))
                .orElse(null);
    }

    private BizAppInstallation installationOf(UUID tenantId, String appKind) {
        return installationRepository.findByTenantIdAndKind(tenantId, appKind)
                .orElseThrow(() -> new ResourceNotFoundException("App " + appKind + " is not installed"));
    }

    private BizAppInstallation activeInstallationForWrite(UUID tenantId, String appKind, boolean admin,
            String entity) {
        BizAppInstallation installation = installationRepository
                .findByTenantIdAndKindAndStatus(tenantId, appKind, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("App " + appKind + " is not active"));
        Manifest manifest = manifestParser.parse(installation.getManifest());
        if (!canWrite(manifest, admin)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "The manifest does not grant write to this role");
        }
        ensureEntity(installation, appKind, entity);
        return installation;
    }

    private void ensureEntity(BizAppInstallation installation, String appKind, String entity) {
        Manifest manifest = manifestParser.parse(installation.getManifest());
        boolean known = manifest.entities() != null && manifest.entities().stream()
                .map(Entity::name)
                .anyMatch(Predicate.isEqual(entity));
        if (!known) {
            throw new ResourceNotFoundException("Entity " + entity + " is not part of app " + appKind);
        }
    }

    /** Manifest RBAC: write defaults to admins only unless MEMBER is listed. */
    boolean canWrite(Manifest manifest, boolean admin) {
        List<String> write = manifest.rbac() == null ? null : manifest.rbac().get("write");
        if (write == null || write.isEmpty()) {
            return admin;
        }
        return admin || write.contains("MEMBER");
    }

    private RecordView toView(BizAppRecord record, Manifest manifest) {
        return new RecordView(
                record.getId(),
                record.getAppKind(),
                record.getEntity(),
                record.getPayload(),
                record.getRuntime(),
                record.getStatus(),
                record.getCreatedBy(),
                record.getCreatedAt(),
                record.getUpdatedAt());
    }
}
