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

package com.openvie.api.workflow.service;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.model.AuditLog;
import com.openvie.api.common.repository.AuditLogRepository;
import com.openvie.api.document.api.ApprovalStatus;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentApi.ApprovalDocumentSnapshot;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.workflow.api.WorkflowTaskContextApi;
import com.openvie.api.workflow.api.event.WorkflowDecisionEvent;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessDetail;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessLog;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessLogEntry;
import com.openvie.api.workflow.dto.WorkflowDtos.ProcessListResult;
import com.openvie.api.workflow.dto.WorkflowDtos.SlConfigResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.UpdateSlConfigRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkflowProcessView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTaskView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTasks;
import com.openvie.api.workflow.model.WorkflowSlaConfig;
import com.openvie.api.workflow.repository.WorkflowSlaConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The approval workflow application service (issue #8): start processes,
 * claim and complete tasks (approve / reject with reason / hand-off to
 * level 2) against the embedded Flowable engine, with RBAC resolved against
 * the live workspace membership on every call — the SSO bridge issues the
 * same OpenVie workspace-scoped token for every role, so the engine never
 * trusts the client for candidacy.
 *
 * <p>Authority short-circuit (no ceremony where the submitter already holds
 * the reviewing authority): a workspace admin or the org owner publishing a
 * non-sensitive document inside the workspace becomes APPROVED in the same
 * transaction, audited with {@code selfApproved=true}. Self-approval during
 * a real review is refused — the submitter may withdraw (reject) or hand the
 * task upward, but only the org owner, as the terminal authority of the
 * chain, may decide an own submission (audited with {@code selfDecision}).
 * Every reviewed event lands in the {@code audit_logs} trail, surfaced by
 * {@link #processLog}.
 */
@Service
@Slf4j(topic = "WORKFLOW")
@RequiredArgsConstructor
public class WorkflowService {

    public static final String PROCESS_KEY = "eofficeDocumentApproval";
    public static final String LEVEL_1_TASK_KEY = "level1Approval";
    public static final String LEVEL_2_TASK_KEY = "level2Approval";
    public static final String GROUP_WORKSPACE_ADMIN = "WORKSPACE_ADMIN";
    public static final String GROUP_ORG_OWNER = "ORG_OWNER";
    public static final String GROUP_MEMBER = "MEMBER";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final WorkflowSlaConfigRepository slaConfigRepository;
    private final WorkflowProperties properties;
    private final DocumentApi documentApi;
    private final TenantIdentityApi identityApi;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private WorkflowTaskContextApi taskContextApi;

    // ------------------------------------------------------------------
    // Process lifecycle
    // ------------------------------------------------------------------

    @Transactional
    public StartProcessResponse start(UUID workspaceId, UUID userId, StartProcessRequest request) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        ApprovalDocumentSnapshot document =
                documentApi.requireApprovalCandidate(workspaceId, request.documentId());
        SlEffective sla = effectiveSla(membership.orgId());
        String publishTarget = request.publishTarget().toLowerCase(Locale.ROOT);
        // Write-back: the document's own state reflects that review is open, so
        // listings and caches never re-derive it from engine history.
        documentApi.markApprovalPending(workspaceId, document.documentId(), publishTarget);

        Map<String, Object> variables = new HashMap<>();
        variables.put("workspaceId", workspaceId.toString());
        variables.put("organizationId", membership.orgId().toString());
        variables.put("documentId", document.documentId().toString());
        variables.put("initiatorId", userId.toString());
        variables.put("documentFileName", document.fileName());
        variables.put("publishTarget", publishTarget);
        variables.put("reminderDuration", sla.reminderDuration());
        variables.put("escalationDuration", sla.escalationDuration());
        variables.put("approvalOutcome", "PENDING");
        variables.put("escalated", false);

        org.flowable.engine.runtime.ProcessInstance instance = runtimeService
                .startProcessInstanceByKey(PROCESS_KEY, document.documentId().toString(), variables);

        // The scoring delegate runs synchronously inside start, so the level
        // decision, the sensitive flag, and the policy version are already
        // recorded as process variables and land in the audit with it.
        Map<String, Object> state = runtimeService.getVariables(instance.getId());
        boolean sensitive = Boolean.TRUE.equals(state.get("sensitive"));
        int level = ((Number) state.get("approvalLevel")).intValue();
        String policyVersion = String.valueOf(state.get("policyVersion"));

        Task firstTask = firstTaskOf(instance.getId());

        audit(workspaceId, userId, LogAction.WORKFLOW_STARTED, UUID.fromString(instance.getId()),
                Map.of("documentId", document.documentId(),
                        "documentFileName", document.fileName(),
                        "sensitive", sensitive,
                        "approvalLevel", level,
                        "policyVersion", policyVersion,
                        "publishTarget", publishTarget,
                        "slaReminder", sla.reminderDuration(),
                        "slaEscalation", sla.escalationDuration()));

        // Authority short-circuit: an unsensitive workspace-scoped submission
        // by someone who already holds the reviewing authority never queues.
        // The flow still runs its APPROVE path, so history, document state and
        // the audit trail keep one uniform shape, and the reviewer is the
        // submitter themselves — recorded with selfApproved=true.
        if (membership.isWorkspaceAdmin() && !sensitive && "workspace".equals(publishTarget)) {
            Map<String, Object> outcome = new HashMap<>();
            outcome.put("approvalOutcome", "APPROVE");
            outcome.put("rejectReason", "");
            outcome.put("selfApproved", true);
            taskService.complete(firstTask.getId(), outcome);

            // The APPROVE path ended the process synchronously: the runtime
            // execution is gone, so the settled state lives in engine history.
            HistoricProcessInstance finished = historyService
                    .createHistoricProcessInstanceQuery()
                    .processInstanceId(instance.getId())
                    .includeProcessVariables()
                    .singleResult();
            String documentStatus = finished == null || finished.getProcessVariables() == null
                    ? "APPROVED"
                    : String.valueOf(finished.getProcessVariables().get("documentStatus"));
            audit(workspaceId, userId, LogAction.WORKFLOW_APPROVED, UUID.fromString(instance.getId()),
                    Map.of("decision", "APPROVE",
                            "selfApproved", true,
                            "manual", false,
                            "overdue", false,
                            "reason", "Published by the submitting authority inside its own workspace",
                            "approvalLevel", level,
                            "sensitive", sensitive,
                            "policyVersion", policyVersion,
                            "documentId", document.documentId(),
                            "taskDefinitionKey", firstTask.getTaskDefinitionKey()));
            // The APPROVE path ended the process in this same transaction, so
            // the document state and the submitter notice settle with it.
            documentApi.recordApprovalDecision(
                    workspaceId, document.documentId(), ApprovalStatus.APPROVED, userId);
            publishDecisionEvent(membership.orgId(), workspaceId, instance.getId(), document.documentId(),
                    document.fileName(), userId, "APPROVE",
                    "Published by the submitting authority inside its own workspace",
                    level, publishTarget);
            return new StartProcessResponse(
                    UUID.fromString(instance.getId()), document.documentId(), document.fileName(), publishTarget,
                    sensitive, level, policyVersion, sla.reminderDuration(), sla.escalationDuration(),
                    UUID.fromString(firstTask.getId()), true, documentStatus);
        }

        return new StartProcessResponse(
                UUID.fromString(instance.getId()), document.documentId(), document.fileName(), publishTarget,
                sensitive, level, policyVersion, sla.reminderDuration(), sla.escalationDuration(),
                UUID.fromString(firstTask.getId()), false, null);
    }

    @Transactional(readOnly = true)
    public List<WorkflowProcessView> listProcesses(UUID workspaceId, UUID userId, boolean includeFinished) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        List<WorkflowProcessView> views = new ArrayList<>();
        for (HistoricProcessInstance historic : historyQuery(workspaceId, membership, includeFinished)) {
            views.add(processView(workspaceId, historic));
        }
        return views;
    }

    /**
     * Optional server-side paging over the same approval journal, mirroring
     * the document list contract: page defaults to 0, size to
     * {@value #DEFAULT_PAGE_SIZE}, and the total is reported back through the
     * X-Total-Count response header.
     */
    @Transactional(readOnly = true)
    public ProcessListResult listProcessesPage(UUID workspaceId, UUID userId, boolean includeFinished,
            Integer page, Integer size) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        int requestedPage = page == null ? 0 : page;
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (requestedPage < 0) {
            throw new BadRequestException("Page must be zero or greater");
        }
        if (requestedSize < 1 || requestedSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("Size must be between 1 and 100");
        }
        long total = processHistoryQuery(workspaceId, membership, includeFinished).count();
        List<WorkflowProcessView> views = new ArrayList<>();
        for (HistoricProcessInstance historic : processHistoryQuery(workspaceId, membership, includeFinished)
                .listPage(requestedPage * requestedSize, requestedSize)) {
            views.add(processView(workspaceId, historic));
        }
        return new ProcessListResult(views, total);
    }

    @Transactional(readOnly = true)
    public ProcessDetail getProcess(UUID workspaceId, UUID userId, UUID processInstanceId) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId.toString())
                .includeProcessVariables()
                .singleResult();
        if (historic == null) {
            throw new ResourceNotFoundException("Approval process not found");
        }
        if (!belongsToWorkspace(workspaceId,
                String.valueOf(historic.getProcessVariables().get("workspaceId")), historic.getTenantId())) {
            // A known process of another workspace: forbidden, never silently filtered.
            throw new AccessDeniedException("This approval process belongs to another workspace");
        }
        requireCanViewProcess(membership, historic);
        List<WorkspaceTaskView> tasks = new ArrayList<>();
        for (Task task : taskService.createTaskQuery()
                .processInstanceId(processInstanceId.toString())
                .taskUnassigned()
                .orderByTaskCreateTime().asc()
                .list()) {
            tasks.add(taskView(workspaceId, task));
        }
        return new ProcessDetail(processView(workspaceId, historic), tasks);
    }

    /**
     * The audited history of one process: who submitted, claimed, approved,
     * rejected or was reminded by SLA — with the actor's display name
     * resolved server-side. Workspace-scoped: a known process of a foreign
     * workspace stays forbidden, an unknown id stays a 404.
     */
    @Transactional(readOnly = true)
    public ProcessLog processLog(UUID workspaceId, UUID userId, UUID processInstanceId) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId.toString())
                .includeProcessVariables()
                .singleResult();
        if (historic == null) {
            throw new ResourceNotFoundException("Approval process not found");
        }
        if (!belongsToWorkspace(workspaceId,
                String.valueOf(historic.getProcessVariables().get("workspaceId")), historic.getTenantId())) {
            // A known process of another workspace: forbidden, never silently filtered.
            throw new AccessDeniedException("This approval process belongs to another workspace");
        }
        requireCanViewProcess(membership, historic);

        Map<UUID, String> actorNames = new HashMap<>();
        List<ProcessLogEntry> entries = new ArrayList<>();
        for (AuditLog row : auditLogRepository
                .findByTenantIdAndResourceIdOrderByIdAsc(workspaceId, processInstanceId)) {
            UUID actorId = row.getUserId();
            if (actorId != null && !actorNames.containsKey(actorId)) {
                actorNames.put(actorId, actorName(actorId));
            }
            Instant occurredAt = row.getCreatedAt() == null
                    ? null
                    : row.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
            entries.add(new ProcessLogEntry(occurredAt, actorId,
                    actorId == null ? null : actorNames.get(actorId),
                    row.getAction().name(), row.getMetadata()));
        }
        return new ProcessLog(processView(workspaceId, historic), entries);
    }

    /** Display fallback chain for an audit actor: full name, then email. */
    private String actorName(UUID actorId) {
        try {
            TenantIdentityApi.IdentitySnapshot identity = identityApi.findUserById(actorId);
            if (identity == null) {
                return null;
            }
            return identity.fullName() == null || identity.fullName().isBlank()
                    ? identity.email()
                    : identity.fullName();
        } catch (RuntimeException failure) {
            log.warn("Could not resolve actor {} of a workflow audit entry", actorId, failure);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Task lifecycle
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WorkspaceTasks listTasks(UUID workspaceId, UUID userId) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        List<String> groups = candidateGroups(membership);
        String workspace = workspaceId.toString();

        List<String> tenantProcessIds = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(workspace)
                .list()
                .stream()
                .map(org.flowable.engine.runtime.ProcessInstance::getId)
                .toList();

        List<Task> assigned = new ArrayList<>();
        assigned.addAll(taskService.createTaskQuery()
                .processVariableValueEquals("workspaceId", workspace)
                .taskAssignee(userId.toString())
                .includeProcessVariables()
                .orderByTaskCreateTime().asc()
                .list());
        // BizApp approval processes carry the workspace on the engine process
        // tenant; older starts may not have the workspaceId variable yet.
        if (!tenantProcessIds.isEmpty()) {
            assigned.addAll(taskService.createTaskQuery()
                    .processInstanceIdIn(tenantProcessIds)
                    .taskAssignee(userId.toString())
                    .includeProcessVariables()
                    .orderByTaskCreateTime().asc()
                    .list());
        }
        List<WorkspaceTaskView> mine = uniqueTasks(assigned).stream()
                .map(task -> taskView(workspaceId, task)).toList();

        List<WorkspaceTaskView> groupTasks = List.of();
        if (!groups.isEmpty()) {
            List<Task> candidates = new ArrayList<>();
            candidates.addAll(taskService.createTaskQuery()
                    .processVariableValueEquals("workspaceId", workspace)
                    .taskCandidateGroupIn(groups)
                    .taskUnassigned()
                    .includeProcessVariables()
                    .orderByTaskCreateTime().asc()
                    .list());
            if (!tenantProcessIds.isEmpty()) {
                candidates.addAll(taskService.createTaskQuery()
                        .processInstanceIdIn(tenantProcessIds)
                        .taskCandidateGroupIn(groups)
                        .taskUnassigned()
                        .includeProcessVariables()
                        .orderByTaskCreateTime().asc()
                        .list());
            }
            groupTasks = uniqueTasks(candidates).stream()
                    .map(task -> taskView(workspaceId, task)).toList();
        }
        return new WorkspaceTasks(mine, groupTasks);
    }

    private static List<Task> uniqueTasks(List<Task> tasks) {
        Map<String, Task> unique = new java.util.LinkedHashMap<>();
        for (Task task : tasks) {
            unique.putIfAbsent(task.getId(), task);
        }
        return new ArrayList<>(unique.values());
    }

    @Transactional
    public WorkspaceTaskView claim(UUID workspaceId, UUID userId, UUID taskId) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        Task task = requireWorkspaceTask(workspaceId, taskId);

        String requiredGroup = requiredCandidateGroup(task);
        if (!candidateGroups(membership).contains(requiredGroup)) {
            throw new AccessDeniedException(
                    "Not a member of this task's approval group: " + requiredGroup);
        }
        if (task.getAssignee() == null) {
            taskService.claim(taskId.toString(), userId.toString());
            audit(workspaceId, userId, LogAction.WORKFLOW_TASK_CLAIMED,
                    UUID.fromString(task.getProcessInstanceId()),
                    Map.of("taskId", taskId,
                            "approvalLevel", levelOf(task),
                            "policyVersion", variable(task, "policyVersion", "unknown")));
        } else if (!task.getAssignee().equals(userId.toString())) {
            throw new ConflictException("Task already claimed by another approver");
        }
        return taskView(workspaceId, taskService.createTaskQuery()
                .taskId(taskId.toString())
                .includeProcessVariables()
                .singleResult());
    }

    @Transactional
    public CompleteTaskResponse complete(UUID workspaceId, UUID userId, UUID taskId,
            CompleteTaskRequest request) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        Task task = requireWorkspaceTask(workspaceId, taskId);

        String requiredGroup = requiredCandidateGroup(task);
        if (!candidateGroups(membership).contains(requiredGroup)) {
            throw new AccessDeniedException(
                    "Not a member of this task's approval group: " + requiredGroup);
        }

        String decision = request.decision().toUpperCase(Locale.ROOT);
        String reason = request.reason() == null ? "" : request.reason().trim();
        int level = levelOf(task);
        if ("REJECT".equals(decision) && reason.isEmpty()) {
            throw new BadRequestException("A rejection requires a reason");
        }
        if ("ESCALATE".equals(decision) && level != 1) {
            throw new BadRequestException("Only level 1 tasks can be handed off to level 2");
        }

        // A claimed task may only be completed by its claimant. An unclaimed
        // task of the caller's approval group is claim-on-complete.
        String assignee = task.getAssignee();
        if (assignee != null && !assignee.equals(userId.toString())) {
            throw new AccessDeniedException("Task already claimed by another approver");
        }
        if (assignee == null) {
            taskService.claim(taskId.toString(), userId.toString());
        }

        // The initiator may withdraw (reject) or hand the task upward, but a
        // real approval must come from someone else — only the org owner, the
        // terminal authority of the chain, may decide an own submission.
        String initiatorId = variable(task, "initiatorId", null);
        boolean selfDecision = initiatorId != null && userId.toString().equals(initiatorId);
        if (selfDecision && "APPROVE".equals(decision)
                && membership.orgRole() != OrgRole.ORG_OWNER) {
            throw new AccessDeniedException(
                    "You cannot approve your own submission. Hand it to another reviewer or escalate it upward.");
        }

        UUID documentId = uuidOrNull(variable(task, "documentId", null));
        String appKind = variable(task, "appKind", null);
        UUID recordId = uuidOrNull(variable(task, "recordId", null));
        String policyVersion = variable(task, "policyVersion", "unknown");
        boolean sensitive = Boolean.parseBoolean(variable(task, "sensitive", "false"));
        String taskDefinitionKey = task.getTaskDefinitionKey();

        Map<String, Object> outcome = new HashMap<>();
        outcome.put("approvalOutcome", decision);
        outcome.put("decision", decision);
        outcome.put("decidedBy", userId.toString());
        outcome.put("rejectReason", reason);
        if ("ESCALATE".equals(decision)) {
            // The flow passed level 1 on the way to level 2, so journaling keeps
            // the same meaning as the timer escalation: the process was escalated.
            outcome.put("escalated", true);
        }
        if (selfDecision) {
            outcome.put("selfDecision", true);
        }
        taskService.complete(taskId.toString(), outcome);

        LogAction action = switch (decision) {
            case "APPROVE" -> LogAction.WORKFLOW_APPROVED;
            case "REJECT" -> LogAction.WORKFLOW_REJECTED;
            default -> LogAction.WORKFLOW_ESCALATED;
        };
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("decision", decision);
        metadata.put("manual", true);
        metadata.put("overdue", false);
        metadata.put("reason", reason);
        metadata.put("approvalLevel", level);
        metadata.put("sensitive", sensitive);
        metadata.put("policyVersion", policyVersion);
        metadata.put("documentId", documentId);
        if (appKind != null) {
            metadata.put("appKind", appKind);
            metadata.put("recordId", recordId);
        }
        metadata.put("taskDefinitionKey", taskDefinitionKey);
        if (selfDecision) {
            metadata.put("selfDecision", true);
        }
        audit(workspaceId, userId, action, UUID.fromString(task.getProcessInstanceId()), metadata);

        CompleteTaskResponse response = terminalState(taskId, decision, task.getProcessInstanceId(), documentId);
        if (response.processEnded() && documentId != null) {
            // Document approval: write the terminal verdict back and notify the
            // submitter. BizApp processes are settled by their BPMN delegate,
            // so they carry a recordId instead of a documentId.
            ApprovalStatus verdict = "APPROVE".equals(decision)
                    ? ApprovalStatus.APPROVED
                    : ApprovalStatus.REJECTED;
            documentApi.recordApprovalDecision(workspaceId, documentId, verdict, userId);
            if (initiatorId != null) {
                publishDecisionEvent(membership.orgId(), workspaceId, task.getProcessInstanceId(), documentId,
                        variable(task, "documentFileName", null),
                        UUID.fromString(initiatorId),
                        decision, reason, level, variable(task, "publishTarget", null));
            }
        }
        return response;
    }

    // ------------------------------------------------------------------
    // SLA configuration
    // ------------------------------------------------------------------

    @Transactional
    public SlConfigResponse updateSlaConfig(UUID workspaceId, UUID userId,
            UpdateSlConfigRequest request) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        if (membership.orgRole() != OrgRole.ORG_OWNER) {
            throw new AccessDeniedException("Only the organization owner can change the approval SLA");
        }
        if (request.escalationMinutes() < request.reminderMinutes()) {
            throw new BadRequestException("Escalation must not come before the reminder");
        }
        WorkflowSlaConfig config = slaConfigRepository.findByOrganizationId(membership.orgId())
                .orElseGet(WorkflowSlaConfig::new);
        config.setOrganizationId(membership.orgId());
        config.setReminderMinutes(request.reminderMinutes());
        config.setEscalationMinutes(request.escalationMinutes());
        config.setUpdatedBy(userId);
        slaConfigRepository.save(config);

        audit(workspaceId, userId, LogAction.WORKFLOW_SLA_CONFIG_CHANGED, workspaceId, Map.of(
                "reminderMinutes", request.reminderMinutes(),
                "escalationMinutes", request.escalationMinutes()));
        // Answer from the values just written (not a repository re-read): the
        // response must always reflect the update even before the next lookup
        // sees the committed row.
        SlEffective saved = new SlEffective(
                "PT%dM".formatted(request.reminderMinutes()),
                "PT%dM".formatted(request.escalationMinutes()),
                request.reminderMinutes(),
                request.escalationMinutes(),
                java.time.LocalDateTime.now());
        return new SlConfigResponse(
                saved.reminderMinutes(),
                saved.escalationMinutes(),
                inMinutes(properties.getDefaultReminderDuration()),
                inMinutes(properties.getDefaultEscalationDuration()),
                saved.updatedAt());
    }

    @Transactional(readOnly = true)
    public SlConfigResponse effectiveSlaConfig(UUID workspaceId, UUID userId) {
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        SlEffective sla = effectiveSla(membership.orgId());
        return new SlConfigResponse(
                sla.reminderMinutes(),
                sla.escalationMinutes(),
                inMinutes(properties.getDefaultReminderDuration()),
                inMinutes(properties.getDefaultEscalationDuration()),
                sla.updatedAt());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SlEffective effectiveSla(UUID organizationId) {
        WorkflowSlaConfig config = slaConfigRepository.findByOrganizationId(organizationId)
                .orElse(null);
        // Without a per-organization row the defaults from application.yaml
        // are authoritative — also expressed in minutes so the API consumer
        // sees one effective value, not a null.
        return new SlEffective(
                config != null ? "PT%dM".formatted(config.getReminderMinutes())
                        : properties.getDefaultReminderDuration(),
                config != null ? "PT%dM".formatted(config.getEscalationMinutes())
                        : properties.getDefaultEscalationDuration(),
                config != null ? config.getReminderMinutes()
                        : inMinutes(properties.getDefaultReminderDuration()),
                config != null ? config.getEscalationMinutes()
                        : inMinutes(properties.getDefaultEscalationDuration()),
                config != null ? config.getUpdatedAt() : null);
    }

    private HistoricProcessInstanceQuery processHistoryQuery(
            UUID workspaceId, MembershipSnapshot membership, boolean includeFinished) {
        var query = historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("workspaceId", workspaceId.toString());
        // Regular members only see their own submissions; workspace admins and
        // org owners oversee every process in the workspace.
        if (membership != null && !membership.isWorkspaceAdmin()) {
            query.variableValueEquals("initiatorId", membership.userId().toString());
        }
        if (!includeFinished) {
            query.unfinished();
        }
        return query.includeProcessVariables().orderByProcessInstanceStartTime().asc();
    }

    private List<HistoricProcessInstance> historyQuery(
            UUID workspaceId, MembershipSnapshot membership, boolean includeFinished) {
        return processHistoryQuery(workspaceId, membership, includeFinished).list();
    }

    private void requireCanViewProcess(MembershipSnapshot membership, HistoricProcessInstance historic) {
        if (membership == null || membership.isWorkspaceAdmin()) {
            return;
        }
        String initiatorId = String.valueOf(historic.getProcessVariables().get("initiatorId"));
        if (!membership.userId().toString().equals(initiatorId)) {
            throw new AccessDeniedException("You can only view approval processes that you submitted");
        }
    }

    private WorkflowProcessView processView(UUID workspaceId, HistoricProcessInstance historic) {
        Map<String, Object> variables = historic.getProcessVariables();
        boolean ended = historic.getEndTime() != null;
        String appKind = nullableString(variables.get("appKind"));
        String entity = nullableString(variables.get("entity"));
        UUID recordId = uuidOrNull(nullableString(variables.get("recordId")));
        WorkflowTaskContextApi.TaskContext context = null;
        if (taskContextApi != null && appKind != null && recordId != null) {
            context = taskContextApi.describe(workspaceId, appKind, entity, recordId);
        }
        String documentFileName = nullableString(variables.get("documentFileName"));
        String title = context != null ? context.title() : documentFileName;
        if (title == null) {
            title = entity == null ? appKind : entity;
        }
        return new WorkflowProcessView(
                UUID.fromString(historic.getId()),
                uuidOrNull(nullableString(variables.get("documentId"))),
                documentFileName,
                Boolean.TRUE.equals(variables.get("sensitive")),
                ((Number) variables.getOrDefault("approvalLevel", 1)).intValue(),
                nullableString(variables.get("policyVersion")),
                nullableString(variables.get("publishTarget")),
                ended ? nullableString(variables.get("documentStatus")) : "IN_REVIEW",
                Boolean.TRUE.equals(variables.get("escalated")),
                toLocalDateTime(historic.getStartTime()),
                toLocalDateTime(historic.getEndTime()),
                title,
                context == null ? null : context.summary(),
                appKind,
                entity);
    }

    private static String nullableString(Object value) {
        return value == null || String.valueOf(value).isBlank() || "null".equalsIgnoreCase(String.valueOf(value))
                ? null : String.valueOf(value);
    }

    private CompleteTaskResponse terminalState(UUID taskId, String decision, String processInstanceId,
            UUID documentId) {
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .includeProcessVariables()
                .singleResult();
        boolean ended = historic != null && historic.getEndTime() != null;
        String documentStatus = null;
        UUID nextTaskId = null;
        if (ended) {
            documentStatus = nullableString(historic.getProcessVariables().get("documentStatus"));
        } else {
            nextTaskId = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .orderByTaskCreateTime().asc()
                    .list().stream()
                    .findFirst()
                    .map(task -> UUID.fromString(task.getId()))
                    .orElse(null);
        }
        return new CompleteTaskResponse(taskId, decision,
                UUID.fromString(processInstanceId), documentId, ended, documentStatus, nextTaskId);
    }

    private List<String> candidateGroups(MembershipSnapshot membership) {
        List<String> groups = new ArrayList<>();
        // Every workspace member belongs to MEMBER; the elevated roles are
        // additive. Tasks explicitly targeted at MEMBER therefore remain
        // visible to admins rather than becoming an orphaned queue.
        groups.add(GROUP_MEMBER);
        if (membership.workspaceRole() == WorkspaceRole.WORKSPACE_ADMIN
                || membership.orgRole() == OrgRole.ORG_OWNER) {
            groups.add(GROUP_WORKSPACE_ADMIN);
        }
        if (membership.orgRole() == OrgRole.ORG_OWNER) {
            groups.add(GROUP_ORG_OWNER);
        }
        return groups;
    }

    private Task requireWorkspaceTask(UUID workspaceId, UUID taskId) {
        Task task = taskService.createTaskQuery()
                .taskId(taskId.toString())
                .includeProcessVariables()
                .singleResult();
        if (task == null) {
            throw new ResourceNotFoundException("Approval task not found");
        }
        var process = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        String tenantId = process == null ? null : process.getTenantId();
        if (!belongsToWorkspace(workspaceId,
                String.valueOf(task.getProcessVariables().get("workspaceId")), tenantId)) {
            throw new AccessDeniedException("This approval task belongs to another workspace");
        }
        return task;
    }

    private static boolean belongsToWorkspace(UUID workspaceId, String variableWorkspaceId, String tenantId) {
        return workspaceId.toString().equals(variableWorkspaceId)
                || workspaceId.toString().equals(tenantId);
    }

    /**
     * The candidate group actually attached to the task. BizApp approval
     * templates may parameterize their first level with any supported role,
     * so the task key alone is not enough to authorize claim/complete.
     */
    private String requiredCandidateGroup(Task task) {
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        if (links != null) {
            for (IdentityLink link : links) {
                if (IdentityLinkType.CANDIDATE.equals(link.getType()) && link.getGroupId() != null) {
                    return link.getGroupId();
                }
            }
        }
        return LEVEL_2_TASK_KEY.equals(task.getTaskDefinitionKey()) ? GROUP_ORG_OWNER : GROUP_WORKSPACE_ADMIN;
    }

    private int levelOf(Task task) {
        return LEVEL_2_TASK_KEY.equals(task.getTaskDefinitionKey()) ? 2 : 1;
    }

    private WorkspaceTaskView taskView(UUID workspaceId, Task task) {
        String documentId = variable(task, "documentId", null);
        String appKind = variable(task, "appKind", null);
        String entity = variable(task, "entity", null);
        String recordId = variable(task, "recordId", null);
        WorkflowTaskContextApi.TaskContext context = null;
        UUID parsedRecordId = uuidOrNull(recordId);
        if (taskContextApi != null && appKind != null && parsedRecordId != null) {
            context = taskContextApi.describe(workspaceId, appKind, entity, parsedRecordId);
        }
        String recordTitle = context == null ? null : context.title();
        String documentFileName = variable(task, "documentFileName", null);
        if (documentFileName == null) {
            documentFileName = recordTitle;
        }
        return new WorkspaceTaskView(
                UUID.fromString(task.getId()),
                task.getName(),
                task.getTaskDefinitionKey(),
                levelOf(task),
                requiredCandidateGroup(task),
                task.getAssignee(),
                UUID.fromString(task.getProcessInstanceId()),
                uuidOrNull(documentId),
                documentFileName,
                toLocalDateTime(task.getCreateTime()),
                appKind,
                entity,
                parsedRecordId,
                recordTitle,
                context == null ? null : context.summary(),
                context == null ? Map.of() : context.fields());
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(java.util.Date time) {
        return time == null ? null : LocalDateTime.ofInstant(time.toInstant(), java.time.ZoneId.systemDefault());
    }

    private String variable(Task task, String name, String fallback) {
        Object value = task.getProcessVariables().get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    private String requireVariable(Task task, String name) {
        Object value = task.getProcessVariables().get(name);
        if (value == null) {
            throw new ResourceNotFoundException("Process variable missing: " + name);
        }
        return String.valueOf(value);
    }

    private Task firstTaskOf(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .orderByTaskCreateTime().asc()
                .list().get(0);
    }

    private int inMinutes(String isoDuration) {
        return (int) Duration.parse(isoDuration).toMinutes();
    }

    /**
     * Publishes the submitter-facing decision notice. The recipient address is
     * resolved here so the notification module needs no lookup of its own; an
     * account that no longer resolves simply skips the notice.
     */
    private void publishDecisionEvent(UUID organizationId, UUID workspaceId, String processInstanceId,
            UUID documentId, String documentFileName, UUID recipientUserId, String decision,
            String reason, int approvalLevel, String publishTarget) {
        TenantIdentityApi.IdentitySnapshot recipient = identityApi.findUserById(recipientUserId);
        eventPublisher.publishEvent(new WorkflowDecisionEvent(
                organizationId, workspaceId, UUID.fromString(processInstanceId), documentId, documentFileName,
                recipientUserId,
                recipient == null ? null : recipient.email(),
                recipient == null ? null : recipient.fullName(),
                decision, reason, approvalLevel, publishTarget));
    }

    private void audit(UUID tenantId, UUID actorId, LogAction action, UUID resourceId,
            Map<String, Object> metadata) {
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(tenantId)
                .userId(actorId)
                .action(action)
                .resourceType("workflow_process")
                .resourceId(resourceId)
                .metadata(metadata)
                .build());
    }

    private record SlEffective(
            String reminderDuration,
            String escalationDuration,
            Integer reminderMinutes,
            Integer escalationMinutes,
            LocalDateTime updatedAt) {
    }
}
