package com.openvie.api.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.openvie.api.ApiApplication;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
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
import com.openvie.api.workflow.dto.WorkflowDtos.WorkflowProcessView;
import com.openvie.api.workflow.dto.WorkflowDtos.WorkspaceTasks;
import com.openvie.api.workflow.service.WorkflowService;

/**
 * The durability half of the issue #8 contract: the approval process with its
 * claimed task, timers and variables lives in PostgreSQL, not in the JVM — so
 * a full API restart (a brand-new application context over the same database)
 * must rediscover the running process and finish it without any replay.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@ActiveProfiles("test")
class WorkflowRestartDurabilityTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_workflow_restart");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private WorkflowService workflowService;

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

    @BeforeEach
    void seed() {
        orgId = newOrganization();
        workspaceId = newWorkspace("ws-" + suffix());
        adminId = newUser("admin-" + suffix() + "@restart.test", OrgRole.MEMBER,
                WorkspaceRole.WORKSPACE_ADMIN);
        ownerId = newUser("owner-" + suffix() + "@restart.test", OrgRole.ORG_OWNER,
                WorkspaceRole.MEMBER);
    }

    @Test
    void aRunningApprovalSurvivesAFullApiRestartInTheSameDatabase() {
        UUID documentId = seedDocument(workspaceId, adminId, "restart durability memo.txt");

        // Boot 1: start the flow and let an admin claim the level 1 task.
        StartProcessResponse started = workflowService.start(workspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));
        workflowService.claim(workspaceId, adminId, started.taskId());

        // Boot 2: a completely new application context over the same database —
        // the API restarting after a deploy or crash. Options are command-line
        // args (they outrank the application.yaml-pinned active profile and the
        // dev profile's ${POSTGRES_URL} datasource).
        ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ApiApplication.class)
                .run(
                        "--spring.profiles.active=test",
                        "--spring.datasource.url=" + JDBC_URL,
                        "--spring.datasource.username=" + PostgresTestContainer.username(),
                        "--spring.datasource.password=" + PostgresTestContainer.password(),
                        "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                        "--spring.flyway.enabled=true",
                        "--server.port=0");
        try {
            WorkflowService afterRestart = restarted.getBean(WorkflowService.class);

            List<WorkflowProcessView> journal = afterRestart.listProcesses(workspaceId, adminId, false);
            assertEquals(1, journal.size(), "the running process survives the restart");
            assertEquals(started.processInstanceId(), journal.getFirst().processInstanceId());
            assertEquals("IN_REVIEW", journal.getFirst().documentStatus());

            WorkspaceTasks tasks = afterRestart.listTasks(workspaceId, adminId);
            assertEquals(1, tasks.assignedToMe().size(),
                    "the claimed task keeps its assignee across the restart");
            assertEquals(adminId.toString(), tasks.assignedToMe().getFirst().assignee());
            assertEquals(started.taskId(), tasks.assignedToMe().getFirst().taskId());
            assertTrue(tasks.groupTasks().isEmpty(),
                    "the claim survived: the task is no longer in the unassigned group queue");

            // The restarted API finishes the approval — no replay, no rebuild.
            CompleteTaskResponse approved = afterRestart.complete(workspaceId, adminId,
                    started.taskId(), new CompleteTaskRequest("APPROVE", null));
            assertTrue(approved.processEnded());
            assertEquals("APPROVED", approved.documentStatus());

            // Claim state was never duplicated: after the restart the task is spent.
            assertThrows(ResourceNotFoundException.class,
                    () -> afterRestart.claim(workspaceId, ownerId, started.taskId()),
                    "a completed task is gone from the engine tables, no ghost row remains");
            assertEquals(0, restarted.getBean(org.flowable.engine.RuntimeService.class)
                    .createProcessInstanceQuery().processInstanceId(started.processInstanceId().toString())
                    .list().size(), "no ghost process instance remains after completion");
        } finally {
            restarted.close();
        }

        // Boot 1's view confirms the same terminal state committed by boot 2.
        List<WorkflowProcessView> finished = workflowService.listProcesses(
                workspaceId, adminId, true);
        assertEquals("APPROVED",
                finished.stream()
                        .filter(view -> view.processInstanceId().equals(started.processInstanceId()))
                        .findFirst()
                        .orElseThrow()
                        .documentStatus());

        Long startedRows = countAuditAfter(LogAction.WORKFLOW_STARTED, workspaceId);
        assertEquals(1L, startedRows, "the restart must not duplicate the start audit entry");
        Long approvedRows = countAuditAfter(LogAction.WORKFLOW_APPROVED, workspaceId);
        assertTrue(approvedRows >= 1L, "the completed approval is in the audit trail");
    }

    @Test
    void crossWorkspaceTaskAccessStaysForbiddenAfterARestart() {
        UUID documentId = seedDocument(workspaceId, adminId, "forbidden app.txt");
        StartProcessResponse started = workflowService.start(workspaceId, adminId,
                new WorkflowDtos.StartProcessRequest(documentId, "workspace"));

        ConfigurableApplicationContext restarted = new SpringApplicationBuilder(ApiApplication.class)
                .run(
                        "--spring.profiles.active=test",
                        "--spring.datasource.url=" + JDBC_URL,
                        "--spring.datasource.username=" + PostgresTestContainer.username(),
                        "--spring.datasource.password=" + PostgresTestContainer.password(),
                        "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                        "--spring.flyway.enabled=true",
                        "--server.port=0");
        try {
            WorkflowService afterRestart = restarted.getBean(WorkflowService.class);
            // The admin is a member of the fresh workspace: the 403 comes from the
            // task lookup being workspace-scoped, not from an identity failure.
            UUID otherWorkspaceId = newWorkspace("ws-fresh-" + suffix());
            transactions.executeWithoutResult(status -> {
                WorkspaceMember membership = new WorkspaceMember();
                membership.setUser(em.getReference(User.class, adminId));
                membership.setWorkspace(em.getReference(Tenant.class, otherWorkspaceId));
                membership.setRole(WorkspaceRole.MEMBER);
                em.persist(membership);
            });
            em.clear();
            assertThrows(AccessDeniedException.class,
                    () -> afterRestart.claim(otherWorkspaceId, adminId, started.taskId()),
                    "the restarted API re-resolves the live membership, never cached state");
        } finally {
            restarted.close();
        }
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
            organization.setName("Restart Org " + suffix());
            organization.setSlug("org-" + suffix());
            em.persist(organization);
            em.flush();
            id[0] = organization.getId();
        });
        em.clear();
        return id[0];
    }

    private UUID newWorkspace(String slug) {
        UUID[] id = new UUID[1];
        transactions.executeWithoutResult(status -> {
            Tenant workspace = new Tenant();
            workspace.setOrganization(em.getReference(Organization.class, orgId));
            workspace.setName(slug);
            workspace.setSlug(slug);
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            id[0] = workspace.getId();
        });
        em.clear();
        return id[0];
    }

    private UUID newUser(String email, OrgRole orgRole, WorkspaceRole workspaceRole) {
        UUID[] id = new UUID[1];
        transactions.executeWithoutResult(status -> {
            User user = new User();
            user.setOrganization(em.getReference(Organization.class, orgId));
            user.setEmail(email);
            user.setPasswordHash("{noop}not-a-login-path");
            user.setFullName("Restart User");
            user.setRole(orgRole);
            user.setStatus(UserStatus.ACTIVE);
            em.persist(user);
            em.flush();
            id[0] = user.getId();
            if (workspaceRole != null) {
                WorkspaceMember membership = new WorkspaceMember();
                membership.setUser(em.getReference(User.class, id[0]));
                membership.setWorkspace(em.getReference(Tenant.class, workspaceId));
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
            document.setStoragePath("workflow-restart/" + UUID.randomUUID());
            document.setStatus(DocumentStatus.COMPLETED);
            em.persist(document);
            em.flush();
            id[0] = document.getId();
        });
        em.clear();
        return id[0];
    }

    /** Audit rows are async after commit; give the listener a grace period. */
    private Long countAuditAfter(LogAction action, UUID tenant) {
        long deadline = System.currentTimeMillis() + 6000;
        Long count = 0L;
        while (System.currentTimeMillis() < deadline) {
            count = countAudit(action, tenant);
            if (count > 0) {
                return count;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return count;
    }

    private Long countAudit(LogAction action, UUID tenant) {
        return jdbc.queryForObject(
                "select count(*) from audit_logs where action = ? and tenant_id = ?",
                Long.class, action.name(), tenant);
    }
}
