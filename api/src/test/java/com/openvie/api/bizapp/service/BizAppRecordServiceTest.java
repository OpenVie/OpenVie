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
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
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
import com.openvie.api.bizapp.service.block.NotifyBlock;
import com.openvie.api.bizapp.service.block.WebhookOutBlock;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.BizAppStorageStore;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Manifest lifecycle hooks are the load-bearing contract here: an approval
 * hook on create must submit the record, a failing approval must roll the
 * write back, onDecide hooks are best-effort, and an edit must never orphan a
 * live approval process.
 */
class BizAppRecordServiceTest {

    private static final String KIND = "leave";
    private static final String ENTITY = "requests";
    private static final String RAW_KEY = "manifest";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    private BizAppRecordRepository recordRepository;
    private BizAppInstallationRepository installationRepository;
    private BizAppManifestParser parser;
    private BizAppPayloadValidator payloadValidator;
    private BizAppBlockRegistry blockRegistry;
    private BizAppRuntime runtime;
    private WebhookOutBlock webhookOutBlock;
    private BizAppStorageRouter storageRouter;
    private BizAppStorageStore store;
    private ApprovalWorkItemBlock approvalExecutor;
    private NotifyBlock notifyExecutor;

    private BizAppRecordService service;
    private Manifest manifest;

    @BeforeEach
    void setUp() {
        recordRepository = mock(BizAppRecordRepository.class);
        installationRepository = mock(BizAppInstallationRepository.class);
        parser = mock(BizAppManifestParser.class);
        payloadValidator = mock(BizAppPayloadValidator.class);
        blockRegistry = mock(BizAppBlockRegistry.class);
        runtime = mock(BizAppRuntime.class);
        webhookOutBlock = mock(WebhookOutBlock.class);
        storageRouter = mock(BizAppStorageRouter.class);
        store = mock(BizAppStorageStore.class);
        approvalExecutor = mock(ApprovalWorkItemBlock.class);
        notifyExecutor = mock(NotifyBlock.class);

        service = new BizAppRecordService(recordRepository, installationRepository, parser,
                payloadValidator, blockRegistry, runtime, webhookOutBlock, storageRouter);

        manifest = manifest();
        stubInstallation();
        when(payloadValidator.sanitize(any(), anyString(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(storageRouter.route(anyString(), anyString())).thenReturn(store);
        when(store.save(any(BizAppRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(blockRegistry.executor(ApprovalWorkItemBlock.TYPE)).thenReturn(Optional.of(approvalExecutor));
        when(blockRegistry.executor(NotifyBlock.TYPE)).thenReturn(Optional.of(notifyExecutor));
    }

    @Test
    void createRunsTheApprovalHookAndLeavesTheRecordPending() {
        when(approvalExecutor.execute(any(BlockSpec.class), any(BlockContext.class)))
                .thenReturn(new BlockResult("proc-1"));

        RecordView view = service.create(tenantId, memberId, "MEMBER", KIND, ENTITY,
                Map.of("reason", "sick leave"));

        assertEquals("PENDING", view.runtime().get("approvalStatus"));
        assertEquals("proc-1", view.runtime().get("processId"));
        verify(approvalExecutor).execute(any(BlockSpec.class), any(BlockContext.class));
        verify(runtime).audit(eq(tenantId), eq(memberId), eq(LogAction.BIZAPP_RECORD_SUBMITTED),
                eq("bizapp_record"), any(), any());
    }

    @Test
    void aFailingApprovalHookRollsTheRecordWriteBack() {
        when(approvalExecutor.execute(any(BlockSpec.class), any(BlockContext.class)))
                .thenThrow(new ConflictException("This app's approval definition is not deployed"));

        assertThrows(ConflictException.class, () -> service.create(tenantId, memberId, "MEMBER", KIND, ENTITY,
                Map.of("reason", "sick leave")));
        verify(runtime).audit(eq(tenantId), eq(memberId), eq(LogAction.BIZAPP_HOOK_FAILED),
                eq("bizapp_record"), any(), any());
    }

    @Test
    void aFailingNotifyHookIsAuditedButNeverFailsTheWrite() {
        when(approvalExecutor.execute(any(BlockSpec.class), any(BlockContext.class)))
                .thenReturn(new BlockResult("proc-1"));
        when(notifyExecutor.execute(any(BlockSpec.class), any(BlockContext.class)))
                .thenThrow(new IllegalStateException("smtp down"));

        RecordView view = service.create(tenantId, memberId, "MEMBER", KIND, ENTITY,
                Map.of("reason", "sick leave"));

        assertEquals("PENDING", view.runtime().get("approvalStatus"));
        verify(runtime).audit(eq(tenantId), eq(memberId), eq(LogAction.BIZAPP_HOOK_FAILED),
                eq("bizapp_record"), any(), any());
    }

    @Test
    void onDecideInvokesTheNotifyHookAndKeepsTheDecision() {
        BizAppRecord record = pendingRecord("proc-1");
        when(storageRouter.findAny(recordId, KIND)).thenReturn(Optional.of(record));
        when(notifyExecutor.execute(any(BlockSpec.class), any(BlockContext.class)))
                .thenReturn(new BlockResult("notify -> 1 email(s)"));

        service.applyEngineDecision(recordId, KIND,
                Map.of("processId", "proc-1", "decision", "APPROVE", "decidedBy", adminId.toString()),
                Map.of("recordId", recordId.toString()));

        assertEquals("APPROVED", record.getRuntime().get("approvalStatus"));
        verify(notifyExecutor).execute(any(BlockSpec.class), any(BlockContext.class));
        verify(runtime).audit(eq(tenantId), eq(adminId), eq(LogAction.BIZAPP_HOOK_EXECUTED),
                eq("bizapp_record"), any(), any());
    }

    @Test
    void onDecideHookFailureNeverEscapes() {
        BizAppRecord record = pendingRecord("proc-1");
        when(storageRouter.findAny(recordId, KIND)).thenReturn(Optional.of(record));
        when(notifyExecutor.execute(any(BlockSpec.class), any(BlockContext.class)))
                .thenThrow(new IllegalStateException("smtp down"));

        service.applyEngineDecision(recordId, KIND,
                Map.of("processId", "proc-1", "decision", "APPROVE", "decidedBy", adminId.toString()),
                Map.of());

        assertEquals("APPROVED", record.getRuntime().get("approvalStatus"));
        verify(runtime).audit(eq(tenantId), eq(adminId), eq(LogAction.BIZAPP_HOOK_FAILED),
                eq("bizapp_record"), any(), any());
    }

    @Test
    void createPublishesOnlyTheDeclaredWebhookEvent() {
        webhookManifest(List.of("record.created"));

        service.create(tenantId, memberId, "MEMBER", KIND, ENTITY, Map.of("reason", "new request"));

        verify(runtime).lifecycle(eq(orgId), eq(tenantId), eq("bizapp.leave.record.created"), any(), any());
        verify(runtime, never()).lifecycle(eq(orgId), eq(tenantId), eq("bizapp.leave.record.updated"), any(), any());
    }

    @Test
    void createSkipsAWebhookEventThatWasNotSelected() {
        webhookManifest(List.of("record.updated"));

        service.create(tenantId, memberId, "MEMBER", KIND, ENTITY, Map.of("reason", "new request"));

        verify(runtime, never()).lifecycle(eq(orgId), eq(tenantId), eq("bizapp.leave.record.created"), any(), any());
    }

    @Test
    void archivePublishesTheArchivedEventWhenSelected() {
        BizAppRecord record = pendingRecord(null);
        record.setRuntime(Map.of());
        when(store.findById(tenantId, KIND, recordId)).thenReturn(Optional.of(record));
        webhookManifest(List.of("record.archived"));

        service.archive(tenantId, adminId, "WORKSPACE_ADMIN", KIND, ENTITY, recordId);

        verify(runtime).lifecycle(eq(orgId), eq(tenantId), eq("bizapp.leave.record.archived"), any(), any());
    }

    @Test
    void decisionWebhookIsSkippedWhenNotSelected() {
        BizAppRecord record = pendingRecord("proc-1");
        when(storageRouter.findAny(recordId, KIND)).thenReturn(Optional.of(record));
        webhookManifest(List.of("record.created"));

        service.applyEngineDecision(recordId, KIND,
                Map.of("processId", "proc-1", "decision", "APPROVE", "decidedBy", adminId.toString()),
                Map.of());

        verify(runtime, never()).lifecycle(eq(orgId), eq(tenantId), eq("bizapp.leave.record.decided"), any(), any());
    }

    @Test
    void decisionWebhookIsPublishedWhenSelected() {
        BizAppRecord record = pendingRecord("proc-1");
        when(storageRouter.findAny(recordId, KIND)).thenReturn(Optional.of(record));
        webhookManifest(List.of("record.decided"));

        service.applyEngineDecision(recordId, KIND,
                Map.of("processId", "proc-1", "decision", "APPROVE", "decidedBy", adminId.toString()),
                Map.of());

        verify(runtime).lifecycle(eq(orgId), eq(tenantId), eq("bizapp.leave.record.decided"), any(), any());
    }

    @Test
    void updatingAPendingRecordPreservesTheRunningProcess() {
        BizAppRecord record = pendingRecord("proc-1");
        when(store.findById(tenantId, KIND, recordId)).thenReturn(Optional.of(record));

        RecordView view = service.update(tenantId, memberId, "MEMBER", KIND, ENTITY, recordId,
                Map.of("reason", "updated reason"));

        assertEquals("proc-1", view.runtime().get("processId"));
        assertEquals("PENDING", view.runtime().get("approvalStatus"));
    }

    @Test
    void submitRejectsArchivedRecords() {
        BizAppRecord record = pendingRecord(null);
        record.setStatus("ARCHIVED");
        record.setRuntime(Map.of());
        when(store.findById(tenantId, KIND, recordId)).thenReturn(Optional.of(record));

        assertThrows(com.openvie.api.common.exception.custom.BadRequestException.class,
                () -> service.submit(tenantId, memberId, "MEMBER", KIND, ENTITY, recordId));
        verify(approvalExecutor, never()).execute(any(BlockSpec.class), any(BlockContext.class));
    }

    @Test
    void submitRejectsARoleWithoutSubmitCapability() {
        BizAppRecord record = pendingRecord(null);
        record.setRuntime(Map.of());
        when(store.findById(tenantId, KIND, recordId)).thenReturn(Optional.of(record));

        // The fixture grants submit only to MEMBER; WORKSPACE_ADMIN falls back
        // to the explicit list, which excludes it.
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.submit(tenantId, adminId, "WORKSPACE_ADMIN", KIND, ENTITY, recordId));
    }

    private void stubInstallation() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(UUID.randomUUID());
        installation.setOrgId(orgId);
        installation.setTenantId(tenantId);
        installation.setKind(KIND);
        installation.setVersion(1);
        installation.setManifest(Map.of("schema", "bizapp.v1", "kind", KIND));
        installation.setProcessKey("bizapp-" + KIND);
        installation.setStatus("ACTIVE");
        when(installationRepository.findByTenantIdAndKindAndStatus(tenantId, KIND, "ACTIVE"))
                .thenReturn(Optional.of(installation));
        when(parser.parse(installation.getManifest())).thenReturn(manifest);
    }

    private void webhookManifest(java.util.List<String> events) {
        Field reason = new Field("reason", "string", true, 500, null, null);
        Entity entity = new Entity(ENTITY, new Localized("Requests", "Đơn"),
                List.of(reason), Map.of());
        Block webhook = new Block("webhook", "webhook_out", Map.of("events", events));
        Manifest withWebhook = new Manifest("bizapp.v1", KIND, new Localized("Leave", "Nghỉ phép"), 1,
                "icon", "jsonb", Map.of("write", List.of("MEMBER"), "submit", List.of("MEMBER")),
                List.of(entity), List.of(webhook), null);
        when(parser.parse(any())).thenReturn(withWebhook);
    }

    private Manifest manifest() {
        Field reason = new Field("reason", "string", true, 500, null, null);
        Entity entity = new Entity(ENTITY, new Localized("Requests", "Đơn"),
                List.of(reason),
                Map.of("onCreate", List.of("approval", "notify"), "onDecide", List.of("notify")));
        Block approval = new Block("approval", "approval_workitem", Map.of(
                "bpmnTemplate", "processes/bizapp-single-approval.bpmn20.xml",
                "candidateRole", "WORKSPACE_ADMIN"));
        Block notify = new Block("notify", "notify", Map.of("to", List.of("creator")));
        return new Manifest("bizapp.v1", KIND, new Localized("Leave", "Nghỉ phép"), 1, "icon", "jsonb",
                Map.of("write", List.of("MEMBER"), "submit", List.of("MEMBER")),
                List.of(entity), List.of(approval, notify), null);
    }

    private BizAppRecord pendingRecord(String processId) {
        BizAppRecord record = new BizAppRecord();
        record.setId(recordId);
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind(KIND);
        record.setEntity(ENTITY);
        record.setPayload(Map.of("reason", "sick leave"));
        record.setCreatedBy(memberId);
        record.setStatus("ACTIVE");
        Map<String, Object> state = new LinkedHashMap<>();
        if (processId != null) {
            state.put("processId", processId);
            state.put("approvalStatus", "PENDING");
        }
        record.setRuntime(state);
        return record;
    }
}
