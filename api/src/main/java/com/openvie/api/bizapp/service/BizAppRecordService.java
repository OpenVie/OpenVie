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
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
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
import com.openvie.api.bizapp.service.block.ExternalCallBlock;
import com.openvie.api.bizapp.service.block.NotifyBlock;
import com.openvie.api.bizapp.service.block.WebhookOutBlock;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** The manifest capability a data-plane call is fenced on. */
    private enum Capability {
        READ("The manifest does not grant read to this role"),
        WRITE("The manifest does not grant write to this role"),
        DELETE("The manifest does not grant delete to this role"),
        SUBMIT("The manifest does not grant submit to this role");

        private final String denial;

        Capability(String denial) {
            this.denial = denial;
        }
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public RecordView get(UUID tenantId, String appKind, String entity, UUID recordId, String role) {
        BizAppInstallation installation = activeInstallation(tenantId, appKind, entity, role, Capability.READ);
        Manifest manifest = manifestParser.parse(installation.getManifest());
        return toView(resolveForEntity(tenantId, appKind, entity, recordId), manifest);
    }

    @Transactional(readOnly = true)
    public Page<RecordView> list(UUID tenantId, String appKind, String entity, boolean includeArchived,
            int page, int size, String role) {
        BizAppInstallation installation = activeInstallation(tenantId, appKind, entity, role, Capability.READ);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<BizAppRecord> rows = storageRouter.route(appKind, entity)
                .list(tenantId, appKind, entity, includeArchived, PageRequest.of(Math.max(page, 0), safeSize));
        Manifest manifest = manifestParser.parse(installation.getManifest());
        return rows.map(row -> toView(row, manifest));
    }

    // ----------------------------------------------------------------- write

    @Transactional
    public RecordView create(UUID tenantId, UUID userId, String role, String appKind,
            String entity, Map<String, Object> payload) {
        BizAppInstallation installation = activeInstallation(tenantId, appKind, entity, role, Capability.WRITE);
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
        runHooks(manifest, installation, saved, "onCreate", userId, role);
        lifecycleWebhook(tenantId, installation, manifest, saved, "record.created",
                Map.of("created_by", userId.toString()));
        return toView(saved, manifest);
    }

    @Transactional
    public RecordView update(UUID tenantId, UUID userId, String role, String appKind,
            String entity, UUID recordId, Map<String, Object> payload) {
        BizAppInstallation installation = activeInstallation(tenantId, appKind, entity, role, Capability.WRITE);
        BizAppRecord record = resolveForEntity(tenantId, appKind, entity, recordId);
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
        runHooks(manifest, installation, saved, "onUpdate", userId, role);
        lifecycleWebhook(tenantId, installation, manifest, saved, "record.updated",
                Map.of("updated_by", userId.toString(), "fields", clean.keySet()));
        return toView(saved, manifest);
    }

    @Transactional
    public RecordView archive(UUID tenantId, UUID userId, String role, String appKind,
            String entity, UUID recordId) {
        BizAppInstallation installation = activeInstallation(tenantId, appKind, entity, role, Capability.DELETE);
        BizAppRecord record = resolveForEntity(tenantId, appKind, entity, recordId);
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
    public RecordView submit(UUID tenantId, UUID userId, String role, String appKind,
            String entity, UUID recordId) {
        BizAppInstallation installation = activeInstallation(tenantId, appKind, entity, role, Capability.SUBMIT);
        BizAppRecord record = resolveForEntity(tenantId, appKind, entity, recordId);
        if ("ARCHIVED".equalsIgnoreCase(record.getStatus())) {
            throw new BadRequestException("Archived records cannot be submitted");
        }
        Manifest manifest = manifestParser.parse(installation.getManifest());
        if ("PENDING".equals(approvalStatus(record))) {
            throw new ConflictException("This record is already pending approval");
        }
        startApproval(manifest, installation, record, userId, role, false);
        return toView(record, manifest);
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

        UUID decidedBy = asUuid(decisionState.get("decidedBy"));
        UUID auditor = decidedBy != null ? decidedBy : record.getCreatedBy();
        runtime.audit(record.getTenantId(), auditor, LogAction.BIZAPP_RECORD_DECIDED,
                "bizapp_record", recordId, Map.of("app", appKind, "decision", decisionState.get("decision")));
        runtime.lifecycle(record.getOrgId(), record.getTenantId(),
                "bizapp." + appKind + ".record.decided", recordId, eventData);

        // onDecide hooks are best-effort: the decision is already final, so a
        // failing notification must never roll it back.
        try {
            BizAppInstallation installation = installationRepository
                    .findByTenantIdAndKindAndStatus(record.getTenantId(), appKind, "ACTIVE")
                    .orElse(null);
            if (installation != null) {
                runHooks(manifestParser.parse(installation.getManifest()), installation, record,
                        "onDecide", auditor, null);
            }
        } catch (Exception exception) {
            log.warn("onDecide hooks failed for record {}: {}", recordId, exception.getMessage());
        }
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

    /**
     * Editing a record under review must not orphan the live process: keep the
     * runtime untouched while PENDING, and only clear a settled decision.
     */
    private Map<String, Object> withDecisionReset(BizAppRecord record) {
        Map<String, Object> runtimeState = new LinkedHashMap<>(record.getRuntime() == null ? Map.of() : record.getRuntime());
        if ("PENDING".equals(approvalStatus(record))) {
            return runtimeState;
        }
        runtimeState.remove("processId");
        runtimeState.remove("approvalStatus");
        runtimeState.remove("decision");
        runtimeState.remove("reminderSentAt");
        runtimeState.remove("reminderBookingDate");
        return runtimeState;
    }

    private String approvalStatus(BizAppRecord record) {
        Map<String, Object> state = record.getRuntime();
        Object status = state == null ? null : state.get("approvalStatus");
        return status == null ? null : String.valueOf(status);
    }

    /**
     * Runs the entity's hook targets for one lifecycle event. Failable work
     * runs first (approval, then external calls) so a later notification can
     * never announce a record that the transaction will roll back; notify and
     * webhook failures are audited but never fail the write, and onDecide
     * hooks are always best-effort.
     */
    private void runHooks(Manifest manifest, BizAppInstallation installation, BizAppRecord record,
            String hookKey, UUID actorId, String role) {
        Entity entity = manifest.entities() == null ? null : manifest.entities().stream()
                .filter(candidate -> candidate.name().equals(record.getEntity()))
                .findFirst()
                .orElse(null);
        List<String> targets = entity == null || entity.hooks() == null
                ? List.of() : entity.hooks().getOrDefault(hookKey, List.of());
        if (targets.isEmpty()) {
            return;
        }
        List<String> approvalTargets = new ArrayList<>();
        List<String> externalCallTargets = new ArrayList<>();
        List<String> deferredTargets = new ArrayList<>();
        for (String target : targets) {
            if (target == null) {
                continue;
            }
            if (target.startsWith("webhook_out:")) {
                deferredTargets.add(target);
                continue;
            }
            Block block = manifest.block(target).orElse(null);
            if (block == null) {
                // Install-time validation rejects unresolvable targets.
                continue;
            }
            if (ApprovalWorkItemBlock.TYPE.equals(block.type())) {
                approvalTargets.add(target);
            } else if (ExternalCallBlock.TYPE.equals(block.type())) {
                externalCallTargets.add(target);
            } else {
                deferredTargets.add(target);
            }
        }
        boolean automaticSubmit = "onCreate".equals(hookKey) || "onUpdate".equals(hookKey);
        for (String target : approvalTargets) {
            if (!automaticSubmit) {
                continue;
            }
            runHookTarget(manifest, installation, record, hookKey, actorId, role, target, true);
        }
        for (String target : externalCallTargets) {
            runHookTarget(manifest, installation, record, hookKey, actorId, role, target, false);
        }
        for (String target : deferredTargets) {
            runHookTarget(manifest, installation, record, hookKey, actorId, role, target, false);
        }
    }

    private void runHookTarget(Manifest manifest, BizAppInstallation installation, BizAppRecord record,
            String hookKey, UUID actorId, String role, String target, boolean automaticApproval) {
        Block block = target.startsWith("webhook_out:") ? null : manifest.block(target).orElse(null);
        String blockId = block != null ? block.id() : target;
        boolean bestEffortOnly = "onDecide".equals(hookKey)
                || target.startsWith("webhook_out:")
                || (block != null && (NotifyBlock.TYPE.equals(block.type())
                        || WebhookOutBlock.TYPE.equals(block.type())));
        try {
            String result;
            if (target.startsWith("webhook_out:")) {
                String eventKind = target.substring("webhook_out:".length());
                webhookOutBlock.publish(installation.getKind(), eventKind,
                        contextFor(installation, manifest, record, actorId), null);
                result = "webhook_out " + eventKind;
            } else if (block == null) {
                return;
            } else if (ApprovalWorkItemBlock.TYPE.equals(block.type())) {
                startApproval(manifest, installation, record, actorId, role, automaticApproval);
                result = "approval started";
            } else {
                BlockResult execution = executeBlock(manifest, installation, record, actorId, block);
                result = execution == null ? "" : String.valueOf(execution.summary());
                if (execution != null && execution.runtimeUpdates() != null
                        && !execution.runtimeUpdates().isEmpty()) {
                    Map<String, Object> runtimeState = new LinkedHashMap<>(
                            record.getRuntime() == null ? Map.of() : record.getRuntime());
                    runtimeState.putAll(execution.runtimeUpdates());
                    record.setRuntime(runtimeState);
                    storageRouter.route(record.getAppKind(), record.getEntity()).save(record);
                }
            }
            runtime.audit(record.getTenantId(), actorId, LogAction.BIZAPP_HOOK_EXECUTED, "bizapp_record",
                    record.getId(), Map.of("hook", hookKey, "block", blockId, "result", result));
        } catch (RuntimeException exception) {
            log.warn("Hook {} target {} failed for record {}: {}", hookKey, target,
                    record.getId(), exception.getMessage());
            runtime.audit(record.getTenantId(), actorId, LogAction.BIZAPP_HOOK_FAILED, "bizapp_record",
                    record.getId(), Map.of("hook", hookKey, "block", blockId,
                            "error", String.valueOf(exception.getMessage())));
            if (!bestEffortOnly) {
                throw exception;
            }
        }
    }

    private BlockResult executeBlock(Manifest manifest, BizAppInstallation installation,
            BizAppRecord record, UUID actorId, Block block) {
        return blockRegistry.executor(block.type())
                .orElseThrow(() -> new BadRequestException(
                        "Hook target " + block.id() + " has no executor"))
                .execute(new BlockSpec(block.id(), block.params()),
                        contextFor(installation, manifest, record, actorId));
    }

    /**
     * Starts the app's single approval process for one record. Auto-submit
     * (onCreate/onUpdate) and explicit submit share this path so the manifest
     * capability fence and the duplicate-process guard are identical.
     */
    private void startApproval(Manifest manifest, BizAppInstallation installation, BizAppRecord record,
            UUID actorId, String role, boolean automatic) {
        if (!canSubmit(manifest, role)) {
            throw new AccessDeniedException("The manifest does not grant submit to this role");
        }
        if ("PENDING".equals(approvalStatus(record))) {
            runtime.audit(record.getTenantId(), actorId, LogAction.BIZAPP_RECORD_SUBMITTED, "bizapp_record",
                    record.getId(), Map.of("app", record.getAppKind(), "entity", record.getEntity(),
                            "skipped", "already pending"));
            return;
        }
        Block approval = manifest.blocks() == null ? null : manifest.blocks().stream()
                .filter(block -> ApprovalWorkItemBlock.TYPE.equals(block.type()))
                .findFirst()
                .orElse(null);
        if (approval == null) {
            throw new BadRequestException("This app has no approval_workitem block; nothing to submit");
        }
        BlockResult result = blockRegistry.executor(ApprovalWorkItemBlock.TYPE)
                .orElseThrow(() -> new BadRequestException("approval_workitem executor is not registered"))
                .execute(new BlockSpec(approval.id(), approval.params()),
                        contextFor(installation, manifest, record, actorId));
        Map<String, Object> runtimeState = new LinkedHashMap<>(
                record.getRuntime() == null ? Map.of() : record.getRuntime());
        runtimeState.put("processId", result == null ? null : result.summary());
        runtimeState.put("approvalStatus", "PENDING");
        record.setRuntime(runtimeState);
        record.setUpdatedBy(actorId);
        storageRouter.route(record.getAppKind(), record.getEntity()).save(record);

        runtime.audit(record.getTenantId(), actorId, LogAction.BIZAPP_RECORD_SUBMITTED, "bizapp_record",
                record.getId(), Map.of("app", record.getAppKind(), "entity", record.getEntity(),
                        "process", String.valueOf(result == null ? null : result.summary()),
                        "automatic", automatic));
        lifecycleWebhook(record.getTenantId(), installation, manifest, record, "record.submitted",
                Map.of("submitted_by", actorId.toString()));
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
                installation.getVersion(),
                record.getCreatedBy(),
                fieldLabels(manifest, record.getEntity()));
    }

    /** Field name -> localized label for the record's entity (vi, then en, then name). */
    private Map<String, String> fieldLabels(Manifest manifest, String entityName) {
        Entity entity = manifest.entities() == null ? null : manifest.entities().stream()
                .filter(candidate -> candidate.name().equals(entityName))
                .findFirst()
                .orElse(null);
        if (entity == null || entity.fields() == null) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (Field field : entity.fields()) {
            String label = null;
            if (field.label() != null) {
                label = field.label().vi() != null && !field.label().vi().isBlank()
                        ? field.label().vi() : field.label().en();
            }
            labels.put(field.name(), label != null && !label.isBlank() ? label : field.name());
        }
        return labels;
    }

    private BizAppRecord resolveForEntity(UUID tenantId, String appKind, String entity, UUID recordId) {
        return storageRouter.route(appKind, entity).findById(tenantId, appKind, recordId)
                .filter(record -> entity.equals(record.getEntity()))
                .orElseThrow(() -> new ResourceNotFoundException("Record not found in this app"));
    }

    private BizAppRecord findAny(UUID recordId, String appKind) {
        return storageRouter.findAny(recordId, appKind).orElse(null);
    }

    private BizAppInstallation activeInstallation(UUID tenantId, String appKind, String entity,
            String role, Capability capability) {
        BizAppInstallation installation = installationRepository
                .findByTenantIdAndKindAndStatus(tenantId, appKind, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("App " + appKind + " is not active"));
        Manifest manifest = manifestParser.parse(installation.getManifest());
        boolean allowed = switch (capability) {
            case READ -> canRead(manifest, role);
            case WRITE -> canWrite(manifest, role);
            case DELETE -> canDelete(manifest, role);
            case SUBMIT -> canSubmit(manifest, role);
        };
        if (!allowed) {
            throw new AccessDeniedException(capability.denial);
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

    // ------------------------------------------------------------ capability

    /** Manifest RBAC: read defaults to every workspace role; owners always read. */
    public boolean canRead(Manifest manifest, String role) {
        if (isOwner(role)) {
            return true;
        }
        List<String> read = rbac(manifest, "read");
        return read == null || (role != null && read.contains(role));
    }

    /** Manifest RBAC: write defaults to admins only unless MEMBER is listed. */
    public boolean canWrite(Manifest manifest, String role) {
        if (isOwner(role)) {
            return true;
        }
        List<String> write = rbac(manifest, "write");
        return write == null ? isAdmin(role) : (role != null && write.contains(role));
    }

    public boolean canDelete(Manifest manifest, String role) {
        if (isOwner(role)) {
            return true;
        }
        List<String> delete = rbac(manifest, "delete");
        return delete == null ? isAdmin(role) : (role != null && delete.contains(role));
    }

    public boolean canApprove(Manifest manifest, String role) {
        if (isOwner(role)) {
            return true;
        }
        List<String> approve = rbac(manifest, "approve");
        return approve == null ? isAdmin(role) : (role != null && approve.contains(role));
    }

    /** Submit falls back to write unless the manifest declares an explicit list. */
    public boolean canSubmit(Manifest manifest, String role) {
        if (isOwner(role)) {
            return true;
        }
        List<String> submit = rbac(manifest, "submit");
        if (submit == null) {
            return canWrite(manifest, role);
        }
        return role != null && submit.contains(role);
    }

    private List<String> rbac(Manifest manifest, String capability) {
        if (manifest.rbac() == null) {
            return null;
        }
        List<String> roles = manifest.rbac().get(capability);
        return roles == null || roles.isEmpty() ? null : roles;
    }

    private boolean isOwner(String role) {
        return "ORG_OWNER".equalsIgnoreCase(role);
    }

    private boolean isAdmin(String role) {
        return "WORKSPACE_ADMIN".equalsIgnoreCase(role);
    }

    private UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
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
