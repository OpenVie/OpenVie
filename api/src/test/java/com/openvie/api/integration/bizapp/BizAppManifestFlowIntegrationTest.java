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

package com.openvie.api.integration.bizapp;

import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.service.BizAppInstallService;
import com.openvie.api.bizapp.service.BizAppRecordService;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.testsupport.PostgresTestContainer;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.openvie.api.workflow.dto.EngineDtos.EngineCompleteRequest;
import com.openvie.api.workflow.service.WorkflowEngineService;
import jakarta.persistence.EntityManager;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLinkType;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end manifest flow: a member-created record auto-submits through the
 * declared {@code approval_workitem} hook, the Flowable task is candidate-scoped
 * to the manifest role (WORKSPACE_ADMIN), member claim/complete stays denied,
 * and the terminal decision writes back into the record runtime while the
 * notify hooks leave their audit trail.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        // Timers are not exercised here; a live async executor would fight the
        // test transaction for the SLA jobs.
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false"
})
@ActiveProfiles("test")
class BizAppManifestFlowIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_bizapp_flow");

    private static final String KIND = "leave-flow";
    private static final String ENTITY = "requests";

    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private BizAppInstallService installService;

    @Autowired
    private BizAppRecordService recordService;

    @Autowired
    private WorkflowEngineService engineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    private UUID orgId;
    private UUID workspaceId;
    private UUID ownerId;
    private UUID adminId;
    private UUID memberId;

    @BeforeEach
    void seed() {
        transactions.executeWithoutResult(status -> {
            String suffix = String.valueOf(SUFFIX.incrementAndGet());

            Organization organization = new Organization();
            organization.setName("Flow Org " + suffix);
            organization.setSlug("bizapp-flow-" + suffix);
            em.persist(organization);
            em.flush();
            orgId = organization.getId();

            Tenant workspace = new Tenant();
            workspace.setOrganization(organization);
            workspace.setName("Flow Workspace");
            workspace.setSlug("bizapp-flow-ws-" + suffix);
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            workspaceId = workspace.getId();

            ownerId = user(organization, workspace, "owner-" + suffix, OrgRole.ORG_OWNER,
                    WorkspaceRole.MEMBER);
            adminId = user(organization, workspace, "admin-" + suffix, OrgRole.MEMBER,
                    WorkspaceRole.WORKSPACE_ADMIN);
            memberId = user(organization, workspace, "member-" + suffix, OrgRole.MEMBER,
                    WorkspaceRole.MEMBER);
        });
    }

    @Test
    void memberSubmissionAutoSubmitsAndOnlyTheCandidateRoleDecides() {
        installService.install(workspaceId, orgId, ownerId, flowManifest());

        RecordView created = recordService.create(workspaceId, memberId, "MEMBER", KIND, ENTITY,
                Map.of("reason", "annual leave", "email", "member@example.test"));

        assertEquals("PENDING", created.runtime().get("approvalStatus"));
        String processId = String.valueOf(created.runtime().get("processId"));
        assertNotNull(created.runtime().get("processId"), "the onCreate approval hook starts the process");

        Task task = taskService.createTaskQuery().processInstanceId(processId).singleResult();
        assertNotNull(task, "exactly one approval task is created");
        assertNull(task.getAssignee());
        assertTrue(taskService.getIdentityLinksForTask(task.getId()).stream()
                        .anyMatch(link -> IdentityLinkType.CANDIDATE.equals(link.getType())
                                && "WORKSPACE_ADMIN".equals(link.getGroupId())),
                "the task is candidate-scoped to the manifest candidateRole");
        UUID taskId = UUID.fromString(task.getId());

        // A member is not a candidate: no inbox row, no claim, no completion.
        assertTrue(engineService.listTasks(workspaceId, memberId, null, false, null, 25).tasks().isEmpty());
        assertThrows(AccessDeniedException.class, () -> engineService.claimTask(workspaceId, memberId, taskId, null));
        assertThrows(ConflictException.class, () -> engineService.completeTask(workspaceId, memberId, taskId,
                new EngineCompleteRequest(Map.of("decision", "APPROVE"))));

        // The workspace admin owns the candidate group and can work the task.
        assertEquals(1, engineService.listTasks(workspaceId, adminId, null, false, null, 25).total());
        engineService.claimTask(workspaceId, adminId, taskId, null);
        assertThrows(AccessDeniedException.class, () -> engineService.completeTask(workspaceId, memberId, taskId,
                new EngineCompleteRequest(Map.of("decision", "APPROVE"))),
                "only the assignee may complete a claimed task");

        engineService.completeTask(workspaceId, adminId, taskId, new EngineCompleteRequest(
                Map.of("decision", "APPROVE", "decidedBy", adminId.toString())));

        RecordView decided = recordService.get(workspaceId, KIND, ENTITY, created.id(), "ORG_OWNER");
        assertEquals("APPROVED", decided.runtime().get("approvalStatus"));
        assertEquals("APPROVE", ((Map<?, ?>) decided.runtime().get("decision")).get("decision"));

        awaitAuditRow(LogAction.BIZAPP_RECORD_SUBMITTED, "automatic");
        awaitAuditRow(LogAction.BIZAPP_HOOK_EXECUTED, "notify1");
        awaitAuditRow(LogAction.BIZAPP_HOOK_EXECUTED, "onDecide");
        awaitAuditRow(LogAction.BIZAPP_RECORD_DECIDED, "APPROVE");
    }

    @Test
    void rbacWriteFenceKeepsNonWritersOutEvenWhenTheyCanRead() {
        installService.install(workspaceId, orgId, ownerId, flowManifest());

        // rbac.write lists MEMBER only; an admin without the capability is denied
        // while still being allowed to read the (empty) registry.
        assertThrows(AccessDeniedException.class, () -> recordService.create(
                workspaceId, adminId, "WORKSPACE_ADMIN", KIND, ENTITY, Map.of("reason", "x")));
        assertTrue(recordService.list(workspaceId, KIND, ENTITY, false, 0, 20, "WORKSPACE_ADMIN")
                .getTotalElements() == 0);
    }

    private UUID user(Organization organization, Tenant workspace, String prefix,
                      OrgRole orgRole, WorkspaceRole workspaceRole) {
        User user = new User();
        user.setOrganization(organization);
        user.setEmail(prefix + "@bizapp-flow.example");
        user.setPasswordHash("{noop}not-used-in-this-suite");
        user.setFullName(prefix);
        user.setRole(orgRole);
        user.setStatus(UserStatus.ACTIVE);
        em.persist(user);
        em.flush();

        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(workspaceRole);
        em.persist(membership);
        return user.getId();
    }

    private Map<String, Object> flowManifest() {
        return Map.of(
                "schema", "bizapp.v1",
                "kind", KIND,
                "name", Map.of("en", "Leave Flow", "vi", "Quy trình nghỉ phép"),
                "version", 1,
                "storage", "jsonb",
                "rbac", Map.of(
                        "read", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"),
                        "write", List.of("ORG_OWNER", "MEMBER"),
                        "submit", List.of("ORG_OWNER", "MEMBER"),
                        "approve", List.of("ORG_OWNER", "WORKSPACE_ADMIN")),
                "entities", List.of(Map.of(
                        "name", ENTITY,
                        "label", Map.of("en", "Requests", "vi", "Đơn"),
                        "fields", List.of(
                                Map.of("name", "reason", "type", "string", "required", true),
                                Map.of("name", "email", "type", "string")),
                        "hooks", Map.of(
                                "onCreate", List.of("approval1", "notify1"),
                                "onDecide", List.of("notify1")))),
                "blocks", List.of(
                        Map.of("id", "approval1", "type", "approval_workitem", "params", Map.of(
                                "bpmnTemplate", "processes/bizapp-single-approval.bpmn20.xml",
                                "candidateRole", "WORKSPACE_ADMIN")),
                        Map.of("id", "notify1", "type", "notify", "params", Map.of(
                                "to", List.of("creator"),
                                "title", "Record update",
                                "subject", "Record update"))));
    }

    /** The audit listener persists AFTER_COMMIT on an executor thread: poll. */
    private void awaitAuditRow(LogAction action, String metadataFragment) {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            Long count = jdbc.queryForObject(
                    "select count(*) from audit_logs where action = ? and tenant_id = ? "
                            + "and metadata::text like ?",
                    Long.class, action.name(), workspaceId, "%" + metadataFragment + "%");
            if (count != null && count > 0) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("audit row " + action + " / " + metadataFragment + " did not appear within the grace period");
    }
}
