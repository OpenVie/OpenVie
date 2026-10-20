package com.openvie.api.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.TaskService;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.exception.custom.UnauthorizedException;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;
import com.openvie.api.document.model.Document;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.enums.KnowledgeBaseStatus;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.KnowledgeBase;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.openvie.api.testsupport.PostgresTestContainer;
import com.openvie.api.workflow.dto.WorkflowDtos;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.CompleteTaskResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.StartProcessResponse;
import com.openvie.api.workflow.dto.WorkflowDtos.UpdateSlConfigRequest;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTasks;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTaskView;
import com.openvie.api.workflow.service.WorkflowService;

/**
 * Issue #8 engine contract against a real PostgreSQL: BPMN routing of the
 * embedded Flowable engine, RBAC on level tasks, explicit cross-workspace 403
 * semantics, the audit trail of every transition, and the SLA timers — all in
 * the same database as the business tables (ADR-0001). Timers are fast-tracked
 * through {@code ManagementService} so the suite stays deterministic.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        // The SLA timers are executed manually through ManagementService; a
        // running async executor would acquire the same moved job and kill
        // one of the two transactions with an optimistic-locking collision.
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false"
})
@ActiveProfiles("test")
class WorkflowEngineIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_workflow_engine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private DocumentApi documentApi;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private UUID orgId;
    private UUID workspaceId;
    private UUID adminId;
    private UUID ownerId;
    private UUID memberId;

    @BeforeEach
    void seedFreshWorkspace() {
        orgId = newOrganization();
        workspaceId = newWorkspace("ws-" + suffix());
        adminId = newUser("admin-" + suffix() + "@workflow.test", OrgRole.MEMBER,
                WorkspaceRole.WORKSPACE_ADMIN, workspaceId);
        ownerId = newUser("owner-" + suffix() + "@workflow.test", OrgRole.ORG_OWNER,
                WorkspaceRole.MEMBER, workspaceId);
        memberId = newUser("member-" + suffix() + "@workflow.test", OrgRole.MEMBER,
                WorkspaceRole.MEMBER, workspaceId);
    }

    // ------------------------------------------------------------------
    // Ordinary flow: submit -> level 1 -> hand-off -> level 2 -> approve
    // ------------------------------------------------------------------

    @Test
    void ordinaryDocumentGoesThroughLevelOneHandOffAndLevelTwoApproval() {
        UUID documentId = seedDocument(workspaceId, memberId, "nasdaq onboarding checklist.pdf");

        // MEMBER submits only: they may start the approval flow.
        StartProcessResponse started = workflowService.start(workspaceId, memberId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        assertEquals(1, started.approvalLevel());
        assertFalse(started.sensitive());
        assertEquals("sensitive-keyword-v1", started.policyVersion());

        UUID level1TaskId = UUID.fromString(taskService.createTaskQuery()
                .processInstanceId(started.processInstanceId().toString())
                .singleResult()
                .getId());

        // The member is never in a candidate group: claim is a 403 and the task stays free.
        assertThrows(AccessDeniedException.class,
                () -> workflowService.claim(workspaceId, memberId, level1TaskId));
        assertNull(task(level1TaskId).getAssignee());

        // The workspace admin finds the unclaimed task in the group queue.
        WorkspaceTasks adminTasks = workflowService.listTasks(workspaceId, adminId);
        assertEquals(1, adminTasks.groupTasks().size(),
                "the group queue presents the task before anyone claims");
        WorkspaceTaskView groupView = adminTasks.groupTasks().getFirst();
        assertEquals(WorkflowService.LEVEL_1_TASK_KEY, groupView.taskDefinitionKey());
        assertEquals("WORKSPACE_ADMIN", groupView.candidateGroup());
        assertNull(groupView.assignee());

        WorkspaceTaskView claimed = workflowService.claim(workspaceId, adminId, level1TaskId);
        assertEquals(adminId.toString(), claimed.assignee());

        // One claim = one assignee: a second claimer hits a conflict; the group
        // keeps seeing the claimed task as a backup but can no longer grab it.
        assertThrows(ConflictException.class,
                () -> workflowService.claim(workspaceId, ownerId, level1TaskId),
                "a second claimer must not take over a claimed task");
        assertTrue(workflowService.listTasks(workspaceId, adminId).groupTasks().isEmpty(),
                "claimed tasks leave the unassigned group queue");

        // The owner does not claim first: claiming stays with the assignee.
        // Hand-off L1 -> L2 by the assignee (level skipping is never offered).
        CompleteTaskResponse handoff = workflowService.complete(workspaceId, adminId,
                level1TaskId, new CompleteTaskRequest("ESCALATE", null));
        assertFalse(handoff.processEnded());
        assertNotNull(handoff.nextTaskId(), "the level 2 task must follow an explicit hand-off");
        assertEquals(WorkflowService.LEVEL_2_TASK_KEY,
                task(handoff.nextTaskId()).getTaskDefinitionKey());
        assertTrue(handoffEscalatedFlag(started.processInstanceId()),
                "hand-off records the escalated flag");
        assertThrows(AccessDeniedException.class,
                () -> workflowService.claim(workspaceId, adminId, handoff.nextTaskId()),
                "level 2 candidates exclude workspace admins");
        assertThrows(AccessDeniedException.class,
                () -> workflowService.complete(workspaceId, adminId, handoff.nextTaskId(),
                        new CompleteTaskRequest("APPROVE", null)));

        WorkspaceTaskView ownedClaim = workflowService.claim(workspaceId, ownerId, handoff.nextTaskId());
        assertEquals(ownerId.toString(), ownedClaim.assignee());

        CompleteTaskResponse approved = workflowService.complete(workspaceId, ownerId,
                handoff.nextTaskId(), new CompleteTaskRequest("APPROVE", null));
        assertTrue(approved.processEnded());
        assertEquals("APPROVED", approved.documentStatus());
        assertNull(approved.nextTaskId());

        awaitAuditRow(LogAction.WORKFLOW_STARTED, workspaceId, null);
        awaitAuditRow(LogAction.WORKFLOW_TASK_CLAIMED, workspaceId, null);
        awaitAuditRow(LogAction.WORKFLOW_ESCALATED, workspaceId, "ESCALATE");
        awaitAuditRow(LogAction.WORKFLOW_APPROVED, workspaceId, null);
        assertTrue(workflowService.listProcesses(workspaceId, adminId, true).stream()
                .anyMatch(view -> view.processInstanceId().equals(started.processInstanceId())
                        && "APPROVED".equals(view.documentStatus())
                        && view.endedAt() != null),
                "the finished process keeps a complete, workspace-scoped journal");
    }

    // ------------------------------------------------------------------
    // Sensitivity routing and rejection
    // ------------------------------------------------------------------

    @Test
    void sensitiveDocumentGoesStraightToTheOwnerLevel() {
        UUID documentId = seedDocument(workspaceId, adminId, "hop dong lao dong bien ban.pdf");

        StartProcessResponse started = workflowService.start(workspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        assertEquals(2, started.approvalLevel());
        assertTrue(started.sensitive());

        // There was never a level 1 task: exactly one owner-level task exists.
        List<Task> active = taskService.createTaskQuery()
                .processInstanceId(started.processInstanceId().toString())
                .list();
        assertEquals(1, active.size());
        assertEquals(WorkflowService.LEVEL_2_TASK_KEY, active.getFirst().getTaskDefinitionKey());

        assertTrue(workflowService.listTasks(workspaceId, adminId).groupTasks().isEmpty(),
                "the workspace admin must not see the owner-only level 2 queue");
        assertThrows(AccessDeniedException.class,
                () -> workflowService.claim(workspaceId, adminId, started.taskId()));

        WorkspaceTaskView claimed = workflowService.claim(workspaceId, ownerId, started.taskId());
        assertEquals(ownerId.toString(), claimed.assignee());
        assertEquals("ORG_OWNER", claimed.candidateGroup());

        CompleteTaskResponse rejected = workflowService.complete(workspaceId, ownerId, started.taskId(),
                new CompleteTaskRequest("REJECT", "do not publish this contract"));
        assertTrue(rejected.processEnded());
        assertEquals("REJECTED", rejected.documentStatus());
        assertNull(rejected.nextTaskId());
        assertEquals("do not publish this contract",
                historyVariables(started.processInstanceId()).get("rejectReason"));

        awaitAuditRow(LogAction.WORKFLOW_STARTED, workspaceId, null);
        awaitAuditRow(LogAction.WORKFLOW_TASK_CLAIMED, workspaceId, null);
        awaitAuditRow(LogAction.WORKFLOW_REJECTED, workspaceId, "do not publish this contract");
    }

    @Test
    void rejectionReasonIsMandatoryEverywhere() {
        // Submitted by the member: an admin's own workspace-scoped submission
        // would already be decided by their authority when it starts.
        UUID documentId = seedDocument(workspaceId, memberId, "nasdaq onboarding checklist.pdf");
        StartProcessResponse started = workflowService.start(workspaceId, memberId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        workflowService.claim(workspaceId, adminId, started.taskId());

        assertThrows(BadRequestException.class,
                () -> workflowService.complete(workspaceId, adminId, started.taskId(),
                        new CompleteTaskRequest("REJECT", "   ")));
        assertEquals(1, taskService.createTaskQuery()
                .processInstanceId(started.processInstanceId().toString()).list().size(),
                "the process is still running after a refused rejection");
        Long rejectedRows = jdbc.queryForObject(
                "select count(*) from audit_logs where action = ? and tenant_id = ?",
                Long.class, LogAction.WORKFLOW_REJECTED.name(), workspaceId);
        assertEquals(0L, rejectedRows);
    }

    // ------------------------------------------------------------------
    // Cross-workspace
    // ------------------------------------------------------------------

    @Test
    void foreignApprovalArtifactsAreExplicitlyForbidden() {
        UUID otherWorkspaceId = newWorkspace("ws-other-" + suffix());
        UUID otherDocumentId = seedDocument(otherWorkspaceId, adminId, "other workspace policy.pdf");

        // The admin is a plain member of the other workspace too (submit rights only).
        transactions.executeWithoutResult(status -> {
            WorkspaceMember membership = new WorkspaceMember();
            membership.setUser(em.getReference(User.class, adminId));
            membership.setWorkspace(em.getReference(Tenant.class, otherWorkspaceId));
            membership.setRole(WorkspaceRole.MEMBER);
            em.persist(membership);
        });
        em.clear();

        // A user without a workspace membership cannot start anything at all.
        UUID strangerId = newUser("stranger-" + suffix() + "@workflow.test", OrgRole.MEMBER, null,
                workspaceId);
        assertThrows(UnauthorizedException.class,
                () -> workflowService.start(workspaceId, strangerId,
                        new WorkflowDtos.StartProcessRequest(UUID.randomUUID(), "workspace")));

        StartProcessResponse foreignProcess = workflowService.start(otherWorkspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(otherDocumentId, "shared"));
        assertEquals(2, foreignProcess.approvalLevel(),
                "a shared publish target needs two levels regardless of the file name");

        // Acting from workspace A on workspace B artifacts: explicit 403, never silence.
        assertThrows(AccessDeniedException.class,
                () -> workflowService.claim(workspaceId, adminId, foreignProcess.taskId()),
                "a member of workspace B must not act on workspace A's task (403)");
        assertThrows(AccessDeniedException.class,
                () -> workflowService.getProcess(workspaceId, adminId, foreignProcess.processInstanceId()));
        assertThrows(AccessDeniedException.class,
                () -> workflowService.complete(workspaceId, adminId, foreignProcess.taskId(),
                        new CompleteTaskRequest("APPROVE", null)));
        assertThrows(ResourceNotFoundException.class,
                () -> documentApi.requireApprovalCandidate(workspaceId, foreignProcess.documentId()),
                "cross-workspace documents never leak into another workspace's submission");
    }

    // ------------------------------------------------------------------
    // SLA timers (fast-tracked through ManagementService)
    // ------------------------------------------------------------------

    @Test
    void slaTimersReminderAndEscalationFireThroughTheEmbeddedEngine() {
        // Member-submitted like the other reviewed flows: the submitting
        // authority fast-path would short-circuit the timers it is testing.
        UUID documentId = seedDocument(workspaceId, memberId, "nasdaq onboarding checklist.pdf");
        StartProcessResponse started = workflowService.start(workspaceId, memberId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        String pid = started.processInstanceId().toString();
        workflowService.claim(workspaceId, adminId, started.taskId());

        // 24h reminder: non-interrupting, the level 1 task stays actionable.
        Job reminder = timerJob(pid, "reminderTimer");
        assertNotNull(reminder, "the reminder boundary must have created a timer job");
        Job movedReminder = managementService.moveTimerToExecutableJob(reminder.getId());
        managementService.executeJob(movedReminder.getId());
        assertEquals(WorkflowService.LEVEL_1_TASK_KEY, task(started.taskId()).getTaskDefinitionKey(),
                "the reminder must leave the level 1 task untouched");
        awaitAuditRow(LogAction.WORKFLOW_REMINDER_SENT, workspaceId, null);

        // 48h escalation: interrupting, the level 1 task is replaced by level 2.
        Job escalation = timerJob(pid, "escalationTimer");
        assertNotNull(escalation, "the escalation boundary must have created a timer job");
        Job movedEscalation = managementService.moveTimerToExecutableJob(escalation.getId());
        managementService.executeJob(movedEscalation.getId());
        em.clear();
        List<Task> after = taskService.createTaskQuery().processInstanceId(pid).list();
        assertEquals(1, after.size());
        assertEquals(WorkflowService.LEVEL_2_TASK_KEY, after.getFirst().getTaskDefinitionKey(),
                "the level 1 task that ran past its SLA must be gone");
        assertTrue(Boolean.TRUE.equals(historyVariables(started.processInstanceId()).get("escalated")));
        awaitAuditRow(LogAction.WORKFLOW_ESCALATED, workspaceId, "SLA overdue");
        Long manualFalse = jdbc.queryForObject(
                "select count(*) from audit_logs where action = ? and tenant_id = ? "
                        + "and metadata::text like '%\"manual\": false%'",
                Long.class, LogAction.WORKFLOW_ESCALATED.name(), workspaceId);
        assertEquals(1L, manualFalse, "a timer escalation is never a manual decision");

        // The escalated route still finishes the process.
        String escalatedTaskId = after.getFirst().getId();
        workflowService.claim(workspaceId, ownerId, UUID.fromString(escalatedTaskId));
        CompleteTaskResponse approved = workflowService.complete(workspaceId, ownerId,
                UUID.fromString(escalatedTaskId), new CompleteTaskRequest("APPROVE", null));
        assertTrue(approved.processEnded());
        assertEquals("APPROVED", approved.documentStatus());
    }

    // ------------------------------------------------------------------
    // Authority short-circuit and the self-approval guard
    // ------------------------------------------------------------------

    @Test
    void theSubmittingAuthorityDecidesItsOwnWorkspaceScopedSubmissionAtStart() {
        UUID documentId = seedDocument(workspaceId, adminId, "internal onboarding memo.txt");

        StartProcessResponse started = workflowService.start(workspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        assertTrue(started.autoApproved());
        assertEquals("APPROVED", started.documentStatus());
        assertEquals(0, taskService.createTaskQuery()
                        .processInstanceId(started.processInstanceId().toString()).list().size(),
                "a decided submission leaves no open approval task");
        awaitAuditRow(LogAction.WORKFLOW_STARTED, workspaceId, null);
        awaitAuditRow(LogAction.WORKFLOW_APPROVED, workspaceId, "selfApproved");
    }

    @Test
    void sharedTargetsFromAnAdminRequireTheOrgOwner() {
        UUID documentId = seedDocument(workspaceId, adminId, "external brochure.pdf");

        StartProcessResponse started = workflowService.start(workspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(documentId, "shared"));
        assertFalse(started.autoApproved());
        // A shared target always routes to the org owner: the submitting
        // workspace admin is never a candidate of the owner-only task.
        assertThrows(AccessDeniedException.class,
                () -> workflowService.claim(workspaceId, adminId, started.taskId()));
        assertThrows(AccessDeniedException.class,
                () -> workflowService.complete(workspaceId, adminId, started.taskId(),
                        new CompleteTaskRequest("APPROVE", null)),
                "the submitter's own shared publication cannot be decided by them");

        WorkspaceTaskView ownedClaim = workflowService.claim(workspaceId, ownerId, started.taskId());
        assertEquals(ownerId.toString(), ownedClaim.assignee());
        CompleteTaskResponse approved = workflowService.complete(workspaceId, ownerId,
                started.taskId(), new CompleteTaskRequest("APPROVE", null));
        assertTrue(approved.processEnded());
        assertEquals("APPROVED", approved.documentStatus());

        // The audited trail answers who did what, when — names resolved server-side.
        var log = workflowService.processLog(workspaceId, adminId, started.processInstanceId());
        assertTrue(log.entries().stream().anyMatch(entry ->
                LogAction.WORKFLOW_STARTED.name().equals(entry.action())));
        assertTrue(log.entries().stream().anyMatch(entry ->
                LogAction.WORKFLOW_APPROVED.name().equals(entry.action())
                        && entry.actorId() != null
                        && entry.actorId().equals(ownerId)
                        && entry.actorName() != null
                        && !entry.actorName().isBlank()),
                "the audit trail names the deciding reviewer");
        assertEquals("APPROVED", log.process().documentStatus());
    }

    // ------------------------------------------------------------------
    // SLA configuration
    // ------------------------------------------------------------------

    @Test
    void ownerSlaOverridesWriteTheRowAndShortenFutureTimers() {
        var response = workflowService.updateSlaConfig(workspaceId, ownerId,
                new UpdateSlConfigRequest(1, 2));
        assertEquals(1, response.reminderMinutes());
        assertEquals(2, response.escalationMinutes());

        Map<String, Object> row = jdbc.queryForMap(
                "select reminder_minutes, escalation_minutes, updated_by "
                        + "from workflow_sla_configs where organization_id = ?", orgId);
        assertEquals(1, ((Number) row.get("reminder_minutes")).intValue());
        assertEquals(2, ((Number) row.get("escalation_minutes")).intValue());
        assertEquals(ownerId, UUID.fromString(String.valueOf(row.get("updated_by"))));

        UUID documentId = seedDocument(workspaceId, adminId, "nasdaq onboarding checklist.pdf");
        StartProcessResponse started = workflowService.start(workspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        assertEquals("PT1M", started.slaReminder());
        assertEquals("PT2M", started.slaEscalation());

        awaitAuditRow(LogAction.WORKFLOW_SLA_CONFIG_CHANGED, workspaceId, null);
    }

    @Test
    void workspaceAdminsCannotChangeTheSlaOverride() {
        assertThrows(AccessDeniedException.class,
                () -> workflowService.updateSlaConfig(workspaceId, adminId,
                        new UpdateSlConfigRequest(30, 60)));
        Long rows = jdbc.queryForObject(
                "select count(*) from workflow_sla_configs where organization_id = ?",
                Long.class, orgId);
        assertEquals(0L, rows);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newOrganization() {
        UUID[] id = new UUID[1];
        transactions.executeWithoutResult(status -> {
            Organization organization = new Organization();
            organization.setName("Workflow Org " + suffix());
            organization.setSlug("org-" + suffix());
            em.persist(organization);
            em.flush();
            id[0] = organization.getId();
        });
        em.clear();
        return id[0];
    }

    /** A companion workspace under the already-created organization of this test. */
    private UUID newWorkspace(String slug) {
        UUID[] ids = new UUID[1];
        transactions.executeWithoutResult(status -> {
            Tenant workspace = new Tenant();
            workspace.setOrganization(em.getReference(Organization.class, orgId));
            workspace.setName(slug);
            workspace.setSlug(slug);
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            ids[0] = workspace.getId();
        });
        em.clear();
        return ids[0];
    }

    /** role==null creates the user without any workspace membership. */
    private UUID newUser(String email, OrgRole orgRole, WorkspaceRole workspaceRole, UUID workspace) {
        UUID[] id = new UUID[1];
        transactions.executeWithoutResult(status -> {
            User user = new User();
            user.setOrganization(em.getReference(Organization.class, orgId));
            user.setEmail(email);
            user.setPasswordHash("{noop}not-a-login-path");
            user.setFullName("Workflow User");
            user.setRole(orgRole);
            user.setStatus(UserStatus.ACTIVE);
            em.persist(user);
            em.flush();
            id[0] = user.getId();
            if (workspaceRole != null) {
                WorkspaceMember membership = new WorkspaceMember();
                membership.setUser(em.getReference(User.class, id[0]));
                membership.setWorkspace(em.getReference(Tenant.class, workspace));
                membership.setRole(workspaceRole);
                em.persist(membership);
            }
        });
        em.clear();
        return id[0];
    }

    private UUID seedDocument(UUID workspace, UUID uploaderId, String fileName) {
        UUID[] id = new UUID[1];
        transactions.executeWithoutResult(status -> {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setTenant(em.getReference(Tenant.class, workspace));
            kb.setName("kb-" + suffix());
            kb.setSlug("kb-" + suffix());
            kb.setStatus(KnowledgeBaseStatus.ACTIVE);
            em.persist(kb);
            em.flush();

            Document document = new Document();
            document.setTenantId(workspace);
            document.setUploadedBy(uploaderId);
            document.setKnowledgeBaseId(kb.getId());
            document.setFileName(fileName);
            document.setFileType(fileName.toLowerCase().endsWith(".pdf") ? DocumentType.PDF : DocumentType.TXT);
            document.setFileSizeBytes(24L);
            document.setStoragePath("workflow/" + UUID.randomUUID());
            document.setStatus(DocumentStatus.COMPLETED);
            em.persist(document);
            em.flush();
            id[0] = document.getId();
        });
        em.clear();
        return id[0];
    }

    private Task task(UUID taskId) {
        return taskService.createTaskQuery()
                .taskId(taskId.toString())
                .singleResult();
    }

    private boolean handoffEscalatedFlag(UUID processInstanceId) {
        return Boolean.TRUE.equals(historyVariables(processInstanceId).get("escalated"));
    }

    private Map<String, Object> historyVariables(UUID processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId.toString())
                .includeProcessVariables()
                .singleResult()
                .getProcessVariables();
    }

    private Job timerJob(String processInstanceId, String boundaryElementId) {
        return managementService.createTimerJobQuery()
                .processInstanceId(processInstanceId)
                .list()
                .stream()
                .filter(job -> boundaryElementId.equals(job.getElementId()))
                .findFirst()
                .orElse(null);
    }

    /** The audit listener persists AFTER_COMMIT on an executor thread: poll. */
    private void awaitAuditRow(LogAction action, UUID tenant, String metadataFragment) {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            long count = metadataFragment == null
                    ? countAudit(action, tenant, null)
                    : countAudit(action, tenant, metadataFragment);
            if (count > 0) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("audit row " + action + (metadataFragment == null ? "" : " / " + metadataFragment)
                + " did not appear within the grace period");
    }

    private long countAudit(LogAction action, UUID tenant, String metadataFragment) {
        if (metadataFragment == null) {
            Long count = jdbc.queryForObject(
                    "select count(*) from audit_logs where action = ? and tenant_id = ?",
                    Long.class, action.name(), tenant);
            return count == null ? 0 : count;
        }
        Long count = jdbc.queryForObject(
                "select count(*) from audit_logs where action = ? and tenant_id = ? "
                        + "and metadata::text like ?",
                Long.class, action.name(), tenant, "%" + metadataFragment + "%");
        return count == null ? 0 : count;
    }
}
