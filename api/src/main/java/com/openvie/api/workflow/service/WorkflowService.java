package com.openvie.api.workflow.service;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.model.AuditLog;
import com.openvie.api.common.repository.AuditLogRepository;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentApi.ApprovalDocumentSnapshot;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantIdentityApi;
import com.openvie.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.openvie.api.tenant.api.WorkspaceRole;
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
        identityApi.requireMembership(userId, workspaceId);
        List<WorkflowProcessView> views = new ArrayList<>();
        for (HistoricProcessInstance historic : historyQuery(workspaceId, includeFinished)) {
            views.add(processView(historic));
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
        identityApi.requireMembership(userId, workspaceId);
        int requestedPage = page == null ? 0 : page;
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (requestedPage < 0) {
            throw new BadRequestException("Page must be zero or greater");
        }
        if (requestedSize < 1 || requestedSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("Size must be between 1 and 100");
        }
        long total = processHistoryQuery(workspaceId, includeFinished).count();
        List<WorkflowProcessView> views = new ArrayList<>();
        for (HistoricProcessInstance historic : processHistoryQuery(workspaceId, includeFinished)
                .listPage(requestedPage * requestedSize, requestedSize)) {
            views.add(processView(historic));
        }
        return new ProcessListResult(views, total);
    }

    @Transactional(readOnly = true)
    public ProcessDetail getProcess(UUID workspaceId, UUID userId, UUID processInstanceId) {
        identityApi.requireMembership(userId, workspaceId);
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId.toString())
                .includeProcessVariables()
                .singleResult();
        if (historic == null) {
            throw new ResourceNotFoundException("Approval process not found");
        }
        if (!workspaceId.toString().equals(String.valueOf(historic.getProcessVariables().get("workspaceId")))) {
            // A known process of another workspace: forbidden, never silently filtered.
            throw new AccessDeniedException("This approval process belongs to another workspace");
        }
        List<WorkspaceTaskView> tasks = new ArrayList<>();
        for (Task task : taskService.createTaskQuery()
                .processInstanceId(processInstanceId.toString())
                .taskUnassigned()
                .orderByTaskCreateTime().asc()
                .list()) {
            tasks.add(taskView(task));
        }
        return new ProcessDetail(processView(historic), tasks);
    }

    /**
     * The audited history of one process: who submitted, claimed, approved,
     * rejected or was reminded by SLA — with the actor's display name
     * resolved server-side. Workspace-scoped: a known process of a foreign
     * workspace stays forbidden, an unknown id stays a 404.
     */
    @Transactional(readOnly = true)
    public ProcessLog processLog(UUID workspaceId, UUID userId, UUID processInstanceId) {
        identityApi.requireMembership(userId, workspaceId);
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId.toString())
                .includeProcessVariables()
                .singleResult();
        if (historic == null) {
            throw new ResourceNotFoundException("Approval process not found");
        }
        if (!workspaceId.toString().equals(String.valueOf(historic.getProcessVariables().get("workspaceId")))) {
            // A known process of another workspace: forbidden, never silently filtered.
            throw new AccessDeniedException("This approval process belongs to another workspace");
        }

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
        return new ProcessLog(processView(historic), entries);
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

        List<WorkspaceTaskView> mine = new ArrayList<>();
        for (Task task : taskService.createTaskQuery()
                .processVariableValueEquals("workspaceId", workspaceId.toString())
                .taskAssignee(userId.toString())
                .orderByTaskCreateTime().asc()
                .list()) {
            mine.add(taskView(task));
        }

        List<WorkspaceTaskView> groupTasks = new ArrayList<>();
        if (!groups.isEmpty()) {
            for (Task task : taskService.createTaskQuery()
                    .processVariableValueEquals("workspaceId", workspaceId.toString())
                    .taskCandidateGroupIn(groups)
                    .taskUnassigned()
                    .orderByTaskCreateTime().asc()
                    .list()) {
                groupTasks.add(taskView(task));
            }
        }
        return new WorkspaceTasks(mine, groupTasks);
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
        return taskView(taskService.createTaskQuery()
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
        boolean selfDecision = userId.toString().equals(requireVariable(task, "initiatorId"));
        if (selfDecision && "APPROVE".equals(decision)
                && membership.orgRole() != OrgRole.ORG_OWNER) {
            throw new AccessDeniedException(
                    "You cannot approve your own submission. Hand it to another reviewer or escalate it upward.");
        }

        UUID documentId = UUID.fromString(requireVariable(task, "documentId"));
        String policyVersion = variable(task, "policyVersion", "unknown");
        boolean sensitive = Boolean.parseBoolean(variable(task, "sensitive", "false"));
        String taskDefinitionKey = task.getTaskDefinitionKey();

        Map<String, Object> outcome = new HashMap<>();
        outcome.put("approvalOutcome", decision);
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
        metadata.put("taskDefinitionKey", taskDefinitionKey);
        if (selfDecision) {
            metadata.put("selfDecision", true);
        }
        audit(workspaceId, userId, action, UUID.fromString(task.getProcessInstanceId()), metadata);

        return terminalState(taskId, decision, task.getProcessInstanceId(), documentId);
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

    private HistoricProcessInstanceQuery processHistoryQuery(UUID workspaceId, boolean includeFinished) {
        var query = historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("workspaceId", workspaceId.toString());
        if (!includeFinished) {
            query.unfinished();
        }
        return query.includeProcessVariables().orderByProcessInstanceStartTime().asc();
    }

    private List<HistoricProcessInstance> historyQuery(UUID workspaceId, boolean includeFinished) {
        return processHistoryQuery(workspaceId, includeFinished).list();
    }

    private WorkflowProcessView processView(HistoricProcessInstance historic) {
        Map<String, Object> variables = historic.getProcessVariables();
        boolean ended = historic.getEndTime() != null;
        return new WorkflowProcessView(
                UUID.fromString(historic.getId()),
                UUID.fromString(String.valueOf(variables.get("documentId"))),
                String.valueOf(variables.get("documentFileName")),
                Boolean.TRUE.equals(variables.get("sensitive")),
                ((Number) variables.getOrDefault("approvalLevel", 1)).intValue(),
                String.valueOf(variables.get("policyVersion")),
                String.valueOf(variables.get("publishTarget")),
                ended ? String.valueOf(variables.get("documentStatus")) : "IN_REVIEW",
                Boolean.TRUE.equals(variables.get("escalated")),
                toLocalDateTime(historic.getStartTime()),
                toLocalDateTime(historic.getEndTime()));
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
            documentStatus = String.valueOf(historic.getProcessVariables().get("documentStatus"));
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
        if (!workspaceId.toString().equals(String.valueOf(task.getProcessVariables().get("workspaceId")))) {
            throw new AccessDeniedException("This approval task belongs to another workspace");
        }
        return task;
    }

    /** Routing decision of the level the task sits at, from the BPMN task keys. */
    private String requiredCandidateGroup(Task task) {
        return LEVEL_2_TASK_KEY.equals(task.getTaskDefinitionKey()) ? GROUP_ORG_OWNER : GROUP_WORKSPACE_ADMIN;
    }

    private int levelOf(Task task) {
        return LEVEL_2_TASK_KEY.equals(task.getTaskDefinitionKey()) ? 2 : 1;
    }

    private WorkspaceTaskView taskView(Task task) {
        String documentId = variable(task, "documentId", null);
        return new WorkspaceTaskView(
                UUID.fromString(task.getId()),
                task.getName(),
                task.getTaskDefinitionKey(),
                levelOf(task),
                requiredCandidateGroup(task),
                task.getAssignee(),
                UUID.fromString(task.getProcessInstanceId()),
                documentId == null ? null : UUID.fromString(documentId),
                variable(task, "documentFileName", null),
                toLocalDateTime(task.getCreateTime()));
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
