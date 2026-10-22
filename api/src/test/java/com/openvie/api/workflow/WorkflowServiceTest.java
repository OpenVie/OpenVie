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

package com.openvie.api.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.exception.custom.UnauthorizedException;
import com.openvie.api.common.model.AuditLog;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentApi.ApprovalDocumentSnapshot;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessListResult;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.UpdateSlConfigRequest;
import com.openvie.api.workflow.model.WorkflowSlaConfig;
import com.openvie.api.workflow.repository.WorkflowSlaConfigRepository;
import com.openvie.api.workflow.service.WorkflowProperties;
import com.openvie.api.workflow.service.WorkflowService;

class WorkflowServiceTest {

    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final HistoryService historyService = mock(HistoryService.class);
    private final WorkflowSlaConfigRepository slaConfigRepository = mock(WorkflowSlaConfigRepository.class);
    private final DocumentApi documentApi = mock(DocumentApi.class);
    private final TenantIdentityApi identityApi = mock(TenantIdentityApi.class);
    private final com.openvie.api.common.repository.AuditLogRepository auditLogRepository =
            mock(com.openvie.api.common.repository.AuditLogRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID otherWorkspaceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID initiatorId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID processInstanceId = UUID.randomUUID();

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(runtimeService, taskService, historyService,
                slaConfigRepository, new WorkflowProperties(), documentApi, identityApi,
                auditLogRepository, eventPublisher);
        lenient().when(slaConfigRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------
    // start
    // ------------------------------------------------------------------

    @Test
    void startPublishesTheLevelDecisionWithThePolicyVersionOntoProcessAndAudit() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        when(documentApi.requireApprovalCandidate(workspaceId, documentId)).thenReturn(
                new ApprovalDocumentSnapshot(documentId, workspaceId, UUID.randomUUID(),
                        "hop-dong-lao-dong.pdf"));
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn(processInstanceId.toString());
        when(runtimeService.startProcessInstanceByKey(eq(WorkflowService.PROCESS_KEY),
                eq(documentId.toString()), any())).thenReturn(instance);
        when(runtimeService.getVariables(processInstanceId.toString())).thenReturn(Map.of(
                "sensitive", true, "approvalLevel", 2, "policyVersion", "sensitive-keyword-v1"));
        Task firstTask = task(WorkflowService.LEVEL_2_TASK_KEY, null,
                Map.of("workspaceId", workspaceId.toString()));
        stubTaskQuery(List.of(firstTask));

        StartProcessResponse response = service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "workspace"));

        assertEquals(2, response.approvalLevel());
        assertTrue(response.sensitive());
        assertEquals("sensitive-keyword-v1", response.policyVersion());
        assertEquals(UUID.fromString(firstTask.getId()), response.taskId());

        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(runtimeService).startProcessInstanceByKey(anyString(), anyString(), captor.capture());
        assertEquals(workspaceId.toString(), captor.getValue().get("workspaceId"));
        assertEquals(organizationId.toString(), captor.getValue().get("organizationId"));
        assertEquals("hop-dong-lao-dong.pdf", captor.getValue().get("documentFileName"));

        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.argThat(event -> {
            AuditLogEvent audit = (AuditLogEvent) event;
            return audit.getAction() == LogAction.WORKFLOW_STARTED
                    && workspaceId.equals(audit.getTenantId())
                    && Boolean.TRUE.equals(audit.getMetadata().get("sensitive"))
                    && audit.getMetadata().get("approvalLevel").equals(2)
                    && "sensitive-keyword-v1".equals(audit.getMetadata().get("policyVersion"));
        }));
    }

    @Test
    void startRejectsNonMembersBeforeTouchingDocuments() {
        when(identityApi.requireMembership(userId, workspaceId))
                .thenThrow(new UnauthorizedException("You are not a member of this workspace"));

        assertThrows(UnauthorizedException.class, () -> service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "workspace")));
        verify(documentApi, never()).requireApprovalCandidate(any(), any());
    }

    @Test
    void startFailsWhenTheDocumentIsUnknown() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        when(documentApi.requireApprovalCandidate(workspaceId, documentId))
                .thenThrow(new ResourceNotFoundException("Document not found"));

        assertThrows(ResourceNotFoundException.class, () -> service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "workspace")));
        verify(runtimeService, never()).startProcessInstanceByKey(anyString(), anyString(), any());
    }

    @Test
    void startAutoPublishesTheWorkspaceScopedSubmissionOfItsOwnAdmin() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        when(documentApi.requireApprovalCandidate(workspaceId, documentId)).thenReturn(
                new ApprovalDocumentSnapshot(documentId, workspaceId, UUID.randomUUID(),
                        "huong-dan-noi-bo.pdf"));
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn(processInstanceId.toString());
        when(runtimeService.startProcessInstanceByKey(eq(WorkflowService.PROCESS_KEY),
                eq(documentId.toString()), any())).thenReturn(instance);
        when(runtimeService.getVariables(processInstanceId.toString())).thenReturn(
                Map.of("sensitive", false, "approvalLevel", 1, "policyVersion", "sensitive-keyword-v1"));
        Task firstTask = task(WorkflowService.LEVEL_1_TASK_KEY, null,
                Map.of("workspaceId", workspaceId.toString()));
        stubTaskQuery(List.of(firstTask));
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentStatus", "APPROVED"),
                LocalDateTime.now());

        StartProcessResponse response = service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "workspace"));

        assertTrue(response.autoApproved());
        assertEquals("APPROVED", response.documentStatus());
        verify(taskService).complete(eq(firstTask.getId()), argThat((java.util.Map<String, Object> outcome) ->
                "APPROVE".equals(outcome.get("approvalOutcome"))
                        && Boolean.TRUE.equals(outcome.get("selfApproved"))));
        verify(eventPublisher).publishEvent(argThat(event ->
                ((AuditLogEvent) event).getAction() == LogAction.WORKFLOW_APPROVED
                        && Boolean.TRUE.equals(((AuditLogEvent) event).getMetadata().get("selfApproved"))
                        && Boolean.FALSE.equals(((AuditLogEvent) event).getMetadata().get("manual"))));
    }

    @Test
    void startKeepsTheReviewFlowForSharedTargetsAndSensitiveDocuments() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        when(documentApi.requireApprovalCandidate(workspaceId, documentId)).thenReturn(
                new ApprovalDocumentSnapshot(documentId, workspaceId, UUID.randomUUID(),
                        "hop-dong-lao-dong.pdf"));
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn(processInstanceId.toString());
        when(runtimeService.startProcessInstanceByKey(eq(WorkflowService.PROCESS_KEY),
                eq(documentId.toString()), any())).thenReturn(instance);
        when(runtimeService.getVariables(processInstanceId.toString())).thenReturn(
                Map.of("sensitive", false, "approvalLevel", 2, "policyVersion", "sensitive-keyword-v1"));
        Task firstTask = task(WorkflowService.LEVEL_2_TASK_KEY, null,
                Map.of("workspaceId", workspaceId.toString()));
        stubTaskQuery(List.of(firstTask));

        StartProcessResponse shared = service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "shared"));
        assertFalse(shared.autoApproved());

        when(runtimeService.getVariables(processInstanceId.toString())).thenReturn(
                Map.of("sensitive", true, "approvalLevel", 2, "policyVersion", "sensitive-keyword-v1"));
        StartProcessResponse sensitive = service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "workspace"));
        assertFalse(sensitive.autoApproved());

        verify(taskService, never()).complete(anyString(), anyMap());
    }

    @Test
    void plainMemberSubmissionsStillQueueForReview() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        when(documentApi.requireApprovalCandidate(workspaceId, documentId)).thenReturn(
                new ApprovalDocumentSnapshot(documentId, workspaceId, UUID.randomUUID(),
                        "bien-ban-hop.pdf"));
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn(processInstanceId.toString());
        when(runtimeService.startProcessInstanceByKey(eq(WorkflowService.PROCESS_KEY),
                eq(documentId.toString()), any())).thenReturn(instance);
        when(runtimeService.getVariables(processInstanceId.toString())).thenReturn(
                Map.of("sensitive", false, "approvalLevel", 1, "policyVersion", "sensitive-keyword-v1"));
        Task firstTask = task(WorkflowService.LEVEL_1_TASK_KEY, null,
                Map.of("workspaceId", workspaceId.toString()));
        stubTaskQuery(List.of(firstTask));

        StartProcessResponse response = service.start(workspaceId, userId,
                new StartProcessRequest(documentId, "workspace"));

        assertFalse(response.autoApproved());
        verify(taskService, never()).complete(anyString(), anyMap());
    }

    // ------------------------------------------------------------------
    // claim
    // ------------------------------------------------------------------

    @Test
    void claimResolvesTasksByIdAndForbidsForeignWorkspacesWithAccessDenied() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, null,
                Map.of("workspaceId", otherWorkspaceId.toString())));

        assertThrows(AccessDeniedException.class, () -> service.claim(workspaceId, userId, taskId),
                "a member of workspace B must not act on workspace A's task (403)");
        verify(taskService, never()).claim(anyString(), anyString());
    }

    @Test
    void claimAnswersNotFoundForUnknownTaskIds() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(null);

        assertThrows(ResourceNotFoundException.class, () -> service.claim(workspaceId, userId, taskId));
    }

    @Test
    void plainMemberCannotClaimLevelOneTasks() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, null,
                Map.of("workspaceId", workspaceId.toString())));

        assertThrows(AccessDeniedException.class, () -> service.claim(workspaceId, userId, taskId));
        verify(taskService, never()).claim(anyString(), anyString());
    }

    @Test
    void orgOwnerClaimsALevelTwoTaskThroughItsOrgRole() {
        member(WorkspaceRole.MEMBER, OrgRole.ORG_OWNER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_2_TASK_KEY, null,
                Map.of("workspaceId", workspaceId.toString())));

        service.claim(workspaceId, userId, taskId);

        verify(taskService).claim(taskId.toString(), userId.toString());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.argThat(event ->
                ((AuditLogEvent) event).getAction() == LogAction.WORKFLOW_TASK_CLAIMED
                        && workspaceId.equals(((AuditLogEvent) event).getTenantId())));
    }

    @Test
    void claimingAClaimedTaskConflicts() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY,
                UUID.randomUUID().toString(), Map.of("workspaceId", workspaceId.toString())));

        assertThrows(ConflictException.class, () -> service.claim(workspaceId, userId, taskId));
        verify(taskService, never()).claim(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // complete
    // ------------------------------------------------------------------

    @Test
    void rejectRequiresAReason() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, null, baseVariables()));

        assertThrows(BadRequestException.class, () -> service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("REJECT", "   ")));
        verify(taskService, never()).complete(anyString(), anyMap());
    }

    @Test
    void escalateIsRejectedOnLevelTwoTasks() {
        member(WorkspaceRole.MEMBER, OrgRole.ORG_OWNER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_2_TASK_KEY, null, baseVariables()));

        assertThrows(BadRequestException.class, () -> service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("ESCALATE", "not mine")));
        verify(taskService, never()).complete(anyString(), anyMap());
    }

    @Test
    void approveEndsAnOrdinaryProcessAndStoresTheDecision() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, userId.toString(), baseVariables()));
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentStatus", "APPROVED"),
                LocalDateTime.now());

        var response = service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("APPROVE", null));

        assertTrue(response.processEnded());
        assertEquals("APPROVED", response.documentStatus());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.argThat(event -> {
            AuditLogEvent audit = (AuditLogEvent) event;
            return audit.getAction() == LogAction.WORKFLOW_APPROVED
                    && "APPROVE".equals(audit.getMetadata().get("decision"))
                    && Boolean.TRUE.equals(audit.getMetadata().get("sensitive"))
                    && "sensitive-keyword-v1".equals(audit.getMetadata().get("policyVersion"));
        }));
    }

    @Test
    void rejectedDecisionRecordsTheReasonAndLeavesTheProcessOpen() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, userId.toString(), baseVariables()));
        // after REJECT the flow reaches recordRejection synchronously, so history ends:
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentStatus", "REJECTED"),
                LocalDateTime.now());

        var response = service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("REJECT", "contract expired"));

        assertTrue(response.processEnded());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.argThat(event -> {
            AuditLogEvent audit = (AuditLogEvent) event;
            return audit.getAction() == LogAction.WORKFLOW_REJECTED
                    && "contract expired".equals(audit.getMetadata().get("reason"));
        }));
    }

    @Test
    void initiatorCannotApproveTheirOwnSubmission() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        Map<String, Object> ownVariables = new java.util.HashMap<>(baseVariables());
        ownVariables.put("initiatorId", userId.toString());
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, userId.toString(), ownVariables));

        assertThrows(AccessDeniedException.class, () -> service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("APPROVE", null)),
                "the submitter must not decide an approval of their own document");
        verify(taskService, never()).complete(anyString(), anyMap());
    }

    @Test
    void theOrgOwnerMayDecideTheirOwnSubmissionAndTheAuditFlagsIt() {
        member(WorkspaceRole.MEMBER, OrgRole.ORG_OWNER);
        Map<String, Object> ownVariables = new java.util.HashMap<>(baseVariables());
        ownVariables.put("initiatorId", userId.toString());
        stubTaskQuerySingle(task(WorkflowService.LEVEL_2_TASK_KEY, userId.toString(), ownVariables));
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentStatus", "APPROVED"),
                LocalDateTime.now());

        var response = service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("APPROVE", null));

        assertTrue(response.processEnded());
        verify(taskService).complete(eq(taskId.toString()), argThat((java.util.Map<String, Object> outcome) ->
                "APPROVE".equals(outcome.get("approvalOutcome"))
                        && Boolean.TRUE.equals(outcome.get("selfDecision"))));
        verify(eventPublisher).publishEvent(argThat(event -> {
            AuditLogEvent audit = (AuditLogEvent) event;
            return audit.getAction() == LogAction.WORKFLOW_APPROVED
                    && Boolean.TRUE.equals(audit.getMetadata().get("selfDecision"));
        }));
    }

    @Test
    void completingSomebodyElsesClaimedTaskIsDenied() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY,
                UUID.randomUUID().toString(), baseVariables()));

        assertThrows(AccessDeniedException.class, () -> service.complete(workspaceId, userId, taskId,
                new CompleteTaskRequest("APPROVE", null)),
                "a claim reserves the task for its claimant");
        verify(taskService, never()).claim(anyString(), anyString());
        verify(taskService, never()).complete(anyString(), anyMap());
    }

    @Test
    void completingAnUnclaimedTaskClaimsItFirst() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        stubTaskQuerySingle(task(WorkflowService.LEVEL_1_TASK_KEY, null, baseVariables()));
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentStatus", "APPROVED"),
                LocalDateTime.now());

        service.complete(workspaceId, userId, taskId, new CompleteTaskRequest("APPROVE", null));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(taskService);
        inOrder.verify(taskService).claim(taskId.toString(), userId.toString());
        inOrder.verify(taskService).complete(eq(taskId.toString()), anyMap());
    }

    // ------------------------------------------------------------------
    // process visibility
    // ------------------------------------------------------------------

    @Test
    void foreignProcessDetailIsForbidden() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        HistoricProcessInstanceQuery historicQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(historic.getProcessVariables()).thenReturn(
                Map.of("workspaceId", otherWorkspaceId.toString()));
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicQuery);
        when(historicQuery.singleResult()).thenReturn(historic);

        assertThrows(AccessDeniedException.class,
                () -> service.getProcess(workspaceId, userId, processInstanceId));
    }

    @Test
    void unknownProcessDetailIsNotFound() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        HistoricProcessInstanceQuery historicQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicQuery);
        when(historicQuery.singleResult()).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
                () -> service.getProcess(workspaceId, userId, processInstanceId));
    }

    // ------------------------------------------------------------------
    // process list paging
    // ------------------------------------------------------------------

    @Test
    void processListPagingCountsAndSlicesTheJournal() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        HistoricProcessInstanceQuery countQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        HistoricProcessInstanceQuery pageQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(countQuery, pageQuery);
        when(countQuery.count()).thenReturn(11L);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(historic.getId()).thenReturn(processInstanceId.toString());
        when(historic.getStartTime()).thenReturn(toJavaDate(LocalDateTime.now().minusMinutes(5)));
        when(historic.getEndTime()).thenReturn(null);
        when(historic.getProcessVariables()).thenReturn(Map.of(
                "workspaceId", workspaceId.toString(), "documentId", documentId.toString(),
                "documentFileName", "hop-dong.pdf", "sensitive", false, "approvalLevel", 1,
                "policyVersion", "sensitive-keyword-v1", "publishTarget", "workspace"));
        when(pageQuery.listPage(10, 10)).thenReturn(List.of(historic));

        ProcessListResult result = service.listProcessesPage(workspaceId, userId, true, 1, 10);

        assertEquals(11L, result.totalCount());
        assertEquals(1, result.processes().size());
        assertEquals(processInstanceId, result.processes().get(0).processInstanceId());
        assertEquals("IN_REVIEW", result.processes().get(0).documentStatus());
    }

    @Test
    void processListPagingKeepsTheUnfinishedFilterAndDefaultWindow() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        HistoricProcessInstanceQuery countQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        HistoricProcessInstanceQuery pageQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(countQuery, pageQuery);
        when(countQuery.count()).thenReturn(0L);
        when(pageQuery.listPage(0, 20)).thenReturn(List.of());

        ProcessListResult result = service.listProcessesPage(workspaceId, userId, false, null, null);

        assertEquals(0L, result.totalCount());
        assertTrue(result.processes().isEmpty());
        verify(pageQuery).unfinished();
        verify(pageQuery).listPage(0, 20);
    }

    @Test
    void processListPagingValidatesTheWindow() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);

        assertThrows(BadRequestException.class,
                () -> service.listProcessesPage(workspaceId, userId, true, -1, 20));
        assertThrows(BadRequestException.class,
                () -> service.listProcessesPage(workspaceId, userId, true, 0, 0));
        assertThrows(BadRequestException.class,
                () -> service.listProcessesPage(workspaceId, userId, true, 0, 101));
    }

    // ------------------------------------------------------------------
    // process log
    // ------------------------------------------------------------------

    @Test
    void processLogReturnsTheOrderedAuditTrailWithResolvedActors() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentId", documentId.toString(),
                        "initiatorId", userId.toString(),
                        "documentFileName", "hop-dong.pdf", "sensitive", false, "approvalLevel", 1,
                        "policyVersion", "sensitive-keyword-v1", "publishTarget", "workspace",
                        "escalated", false),
                null);

        AuditLog submitted = new AuditLog();
        submitted.setTenantId(workspaceId);
        submitted.setUserId(initiatorId);
        submitted.setAction(LogAction.WORKFLOW_STARTED);
        submitted.setResourceType("workflow_process");
        submitted.setResourceId(processInstanceId);
        submitted.setMetadata(Map.of("documentId", documentId.toString(), "publishTarget", "workspace"));
        submitted.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        AuditLog reminder = new AuditLog();
        reminder.setTenantId(workspaceId);
        reminder.setUserId(null);
        reminder.setAction(LogAction.WORKFLOW_REMINDER_SENT);
        reminder.setResourceType("workflow_process");
        reminder.setResourceId(processInstanceId);
        reminder.setMetadata(Map.of("documentId", documentId.toString()));
        reminder.setCreatedAt(LocalDateTime.now());
        when(auditLogRepository.findByTenantIdAndResourceIdOrderByIdAsc(workspaceId, processInstanceId))
                .thenReturn(List.of(submitted, reminder));
        when(identityApi.findUserById(initiatorId)).thenReturn(
                new TenantIdentityApi.IdentitySnapshot(initiatorId, organizationId, "initiator@x.vn",
                        "Nguyen Thi B", OrgRole.MEMBER, "ACTIVE", false));

        var log = service.processLog(workspaceId, userId, processInstanceId);

        assertEquals(2, log.entries().size());
        assertEquals(LogAction.WORKFLOW_STARTED.name(), log.entries().get(0).action());
        assertEquals("Nguyen Thi B", log.entries().get(0).actorName());
        assertEquals(initiatorId, log.entries().get(0).actorId());
        assertEquals(LogAction.WORKFLOW_REMINDER_SENT.name(), log.entries().get(1).action());
        assertNull(log.entries().get(1).actorName());
        assertEquals(1, log.process().approvalLevel());
    }

    @Test
    void processLogOfAForeignWorkspaceIsForbidden() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        HistoricProcessInstanceQuery historicQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(historic.getProcessVariables()).thenReturn(
                Map.of("workspaceId", otherWorkspaceId.toString()));
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicQuery);
        when(historicQuery.singleResult()).thenReturn(historic);

        assertThrows(AccessDeniedException.class,
                () -> service.processLog(workspaceId, userId, processInstanceId));
        verify(auditLogRepository, never()).findByTenantIdAndResourceIdOrderByIdAsc(any(), any());
    }

    @Test
    void processLogOfAnotherMembersProcessIsForbiddenForPlainMember() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);
        stubHistoric(processInstanceId.toString(),
                Map.of("workspaceId", workspaceId.toString(), "documentId", documentId.toString(),
                        "initiatorId", UUID.randomUUID().toString(),
                        "documentFileName", "hop-dong.pdf", "sensitive", false, "approvalLevel", 1,
                        "policyVersion", "sensitive-keyword-v1", "publishTarget", "workspace",
                        "escalated", false),
                null);

        assertThrows(AccessDeniedException.class,
                () -> service.processLog(workspaceId, userId, processInstanceId));
        verify(auditLogRepository, never()).findByTenantIdAndResourceIdOrderByIdAsc(any(), any());
    }

    // ------------------------------------------------------------------
    // SLA configuration
    // ------------------------------------------------------------------

    @Test
    void workspaceAdminsCannotChangeSla() {
        member(WorkspaceRole.WORKSPACE_ADMIN, OrgRole.MEMBER);
        assertThrows(AccessDeniedException.class, () -> service.updateSlaConfig(workspaceId, userId,
                new UpdateSlConfigRequest(30, 60)));
        verify(slaConfigRepository, never()).save(any());
    }

    @Test
    void escalationCannotComeBeforeTheReminder() {
        member(WorkspaceRole.MEMBER, OrgRole.ORG_OWNER);
        assertThrows(BadRequestException.class, () -> service.updateSlaConfig(workspaceId, userId,
                new UpdateSlConfigRequest(60, 30)));
        verify(slaConfigRepository, never()).save(any());
    }

    @Test
    void ownerUpdatePersistsMinutesAndRecordsTheAudit() {
        member(WorkspaceRole.MEMBER, OrgRole.ORG_OWNER);

        var response = service.updateSlaConfig(workspaceId, userId,
                new UpdateSlConfigRequest(1, 2));

        assertEquals(1, response.reminderMinutes());
        assertEquals(2, response.escalationMinutes());
        org.mockito.ArgumentCaptor<WorkflowSlaConfig> saved =
                org.mockito.ArgumentCaptor.forClass(WorkflowSlaConfig.class);
        verify(slaConfigRepository).save(saved.capture());
        assertEquals(organizationId, saved.getValue().getOrganizationId());
        assertEquals(userId, saved.getValue().getUpdatedBy());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.argThat(event ->
                ((AuditLogEvent) event).getAction() == LogAction.WORKFLOW_SLA_CONFIG_CHANGED
                        && workspaceId.equals(((AuditLogEvent) event).getTenantId())));
    }

    @Test
    void effectiveSlaFallsBackToDefaultsWhenNoRowExists() {
        member(WorkspaceRole.MEMBER, OrgRole.MEMBER);

        var response = service.effectiveSlaConfig(workspaceId, userId);

        assertEquals(1440, response.defaultReminderMinutes());
        assertEquals(2880, response.defaultEscalationMinutes());
        assertEquals(1440, response.reminderMinutes());
        assertEquals(2880, response.escalationMinutes());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Map<String, Object> baseVariables() {
        return Map.of(
                "workspaceId", workspaceId.toString(),
                "documentId", documentId.toString(),
                "initiatorId", initiatorId.toString(),
                "sensitive", true,
                "policyVersion", "sensitive-keyword-v1");
    }

    private Task task(String taskDefinitionKey, String assignee, Map<String, Object> variables) {
        Task task = mock(Task.class);
        lenient().when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        lenient().when(task.getAssignee()).thenReturn(assignee);
        lenient().when(task.getProcessVariables()).thenReturn(variables);
        lenient().when(task.getProcessInstanceId()).thenReturn(processInstanceId.toString());
        lenient().when(task.getId()).thenReturn(taskId.toString());
        lenient().when(task.getName()).thenReturn(taskDefinitionKey);
        return task;
    }

    /** Tasks resolve through a fluent query; RETURNS_SELF keeps the chain usable. */
    private TaskQuery stubTaskQuerySingle(Task singleResult) {
        TaskQuery query = mock(TaskQuery.class, RETURNS_SELF);
        lenient().when(query.singleResult()).thenReturn(singleResult);
        lenient().when(taskService.createTaskQuery()).thenReturn(query);
        return query;
    }

    private TaskQuery stubTaskQuery(List<Task> list) {
        TaskQuery query = mock(TaskQuery.class, RETURNS_SELF);
        lenient().when(query.singleResult()).thenReturn(list.isEmpty() ? null : list.get(0));
        lenient().when(query.list()).thenReturn(list);
        lenient().when(taskService.createTaskQuery()).thenReturn(query);
        return query;
    }

    private void stubHistoric(String pid, Map<String, Object> variables, LocalDateTime endTime) {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        lenient().when(historic.getId()).thenReturn(pid);
        lenient().when(historic.getEndTime()).thenReturn(toJavaDate(endTime));
        lenient().when(historic.getProcessVariables()).thenReturn(variables);
        lenient().when(historyService.createHistoricProcessInstanceQuery()).thenReturn(query);
        lenient().when(query.singleResult()).thenReturn(historic);
    }

private java.util.Date toJavaDate(LocalDateTime time) {
        return time == null ? null : java.util.Date.from(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    private void member(WorkspaceRole workspaceRole, OrgRole orgRole) {
        when(identityApi.requireMembership(userId, workspaceId)).thenReturn(
                new MembershipSnapshot(userId, organizationId, workspaceId, orgRole,
                        workspaceRole, "ACTIVE"));
    }
}
