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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.bizapp.service.BizAppConnectorJobService;
import com.openvie.api.bizapp.service.BizAppConnectorWorker;
import com.openvie.api.bizapp.service.BizAppCredentialCipher;
import com.openvie.api.bizapp.service.BizAppInstallService;
import com.openvie.api.bizapp.service.BizAppRecordService;
import com.openvie.api.common.net.SafeOutboundHttp;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end connector flow: a hook/enqueued job calls the (mocked) OT gateway,
 * writes a deduped telemetry row through a typed sink, audits every attempt,
 * and never duplicates a poll result. An archived source record kills the job
 * without writing telemetry.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "flowable.async-executor-activate=false",
        "flowable.async-history-executor-activate=false",
        // Deterministic 32-byte key so the vault is available in this suite.
        "app.bizapps.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        // The suite drives the worker explicitly; keep background ticks out.
        "app.bizapps.connectors.worker-delay-ms=3600000",
        "app.bizapps.connectors.scan-delay-ms=3600000"
})
@ActiveProfiles("test")
class BizAppConnectorFlowIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_bizapp_connector");

    private static final String KIND = "ot-telemetry";
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
    private BizAppConnectorJobService jobService;

    @Autowired
    private BizAppConnectorWorker worker;

    @Autowired
    private BizAppConnectorJobRepository jobRepository;

    @Autowired
    private BizAppCredentialRepository credentialRepository;

    @Autowired
    private BizAppCredentialCipher cipher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private SafeOutboundHttp safeOutboundHttp;

    private UUID orgId;
    private UUID workspaceId;
    private UUID ownerId;

    @BeforeEach
    void seed() {
        transactions.executeWithoutResult(status -> {
            String suffix = String.valueOf(SUFFIX.incrementAndGet());

            Organization organization = new Organization();
            organization.setName("Connector Org " + suffix);
            organization.setSlug("bizapp-connector-" + suffix);
            em.persist(organization);
            em.flush();
            orgId = organization.getId();

            Tenant workspace = new Tenant();
            workspace.setOrganization(organization);
            workspace.setName("Connector Workspace");
            workspace.setSlug("bizapp-connector-ws-" + suffix);
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            workspaceId = workspace.getId();

            User owner = new User();
            owner.setOrganization(organization);
            owner.setEmail("owner-" + suffix + "@bizapp-connector.example");
            owner.setPasswordHash("{noop}not-used-in-this-suite");
            owner.setFullName("Owner");
            owner.setRole(OrgRole.ORG_OWNER);
            owner.setStatus(UserStatus.ACTIVE);
            em.persist(owner);
            em.flush();
            ownerId = owner.getId();

            WorkspaceMember membership = new WorkspaceMember();
            membership.setUser(owner);
            membership.setWorkspace(workspace);
            membership.setRole(WorkspaceRole.WORKSPACE_ADMIN);
            em.persist(membership);
        });
    }

    private void installTelemetryApp() {
        installService.install(workspaceId, orgId, ownerId, otManifest());
        installService.updateConfig(workspaceId, ownerId, KIND,
                Map.of("allowedHosts", List.of("ot-gateway.test")));

        transactions.executeWithoutResult(status -> {
            BizAppCredential credential = new BizAppCredential();
            credential.setOrgId(orgId);
            credential.setName("gateway-key");
            credential.setFormat("api_key");
            credential.setEncryptedData(cipher.encrypt("test-gateway-token"));
            credential.setCreatedBy(ownerId);
            credentialRepository.save(credential);
        });
    }

    @Test
    void typedSinkWritesDedupedTelemetryAndDeadJobsSkipArchivedRecords() {
        installTelemetryApp();
        when(safeOutboundHttp.execute(any())).thenReturn(new SafeOutboundHttp.Response(
                200, "{\"device\":\"d-1\",\"ts\":\"2026-01-01T00:00\",\"temp\":42.5}", Map.of()));

        RecordView device = recordService.create(workspaceId, ownerId, "ORG_OWNER", KIND,
                "devices", Map.of("name", "Boiler 1", "enabled", true));

        UUID firstJob = jobService.enqueue(workspaceId, orgId, KIND, "devices", device.id(),
                "readTemp", ownerId, "HOOK");
        worker.processDueJobs();

        assertEquals("SUCCEEDED", jobRepository.findById(firstJob).orElseThrow().getStatus());
        assertEquals(1, telemetry().getTotalElements());
        RecordView telemetry = telemetry().getContent().get(0);
        assertEquals("d-1", telemetry.payload().get("deviceId"));
        assertEquals("2026-01-01T00:00", telemetry.payload().get("ts"));
        assertEquals(0, new BigDecimal("42.5").compareTo(
                new BigDecimal(String.valueOf(telemetry.payload().get("temperature")))));
        awaitAuditRow("BIZAPP_EXTERNAL_CALL", "SUCCEEDED");

        // A second poll with the same identity updates the row instead of appending.
        when(safeOutboundHttp.execute(any())).thenReturn(new SafeOutboundHttp.Response(
                200, "{\"device\":\"d-1\",\"ts\":\"2026-01-01T00:00\",\"temp\":43.5}", Map.of()));
        UUID secondJob = jobService.enqueue(workspaceId, orgId, KIND, "devices", device.id(),
                "readTemp", ownerId, "HOOK");
        worker.processDueJobs();

        assertEquals("SUCCEEDED", jobRepository.findById(secondJob).orElseThrow().getStatus());
        assertEquals(1, telemetry().getTotalElements(), "dedupe identity must never duplicate rows");
        assertEquals(0, new BigDecimal("43.5").compareTo(new BigDecimal(String.valueOf(
                telemetry().getContent().get(0).payload().get("temperature")))));

        // Archived source: the job dies permanently and telemetry is untouched.
        recordService.archive(workspaceId, ownerId, "ORG_OWNER", KIND, "devices", device.id());
        UUID deadJob = jobService.enqueue(workspaceId, orgId, KIND, "devices", device.id(),
                "readTemp", ownerId, "HOOK");
        worker.processDueJobs();

        assertEquals("DEAD", jobRepository.findById(deadJob).orElseThrow().getStatus());
        assertEquals(1, telemetry().getTotalElements());
        awaitAuditRow("BIZAPP_EXTERNAL_CALL", "record is not active");
    }

    private org.springframework.data.domain.Page<RecordView> telemetry() {
        return recordService.list(workspaceId, KIND, "telemetry", false, 0, 20, "ORG_OWNER");
    }

    private Map<String, Object> otManifest() {
        return Map.of(
                "schema", "bizapp.v1",
                "kind", KIND,
                "name", Map.of("en", "OT Telemetry", "vi", "OT Telemetry"),
                "version", 1,
                "storage", "jsonb",
                "rbac", Map.of(
                        "read", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"),
                        "write", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER")),
                "entities", List.of(
                        Map.of(
                                "name", "devices",
                                "label", Map.of("en", "Devices", "vi", "Devices"),
                                "fields", List.of(
                                        Map.of("name", "name", "type", "string", "required", true),
                                        Map.of("name", "enabled", "type", "boolean"))),
                        Map.of(
                                "name", "telemetry",
                                "label", Map.of("en", "Telemetry", "vi", "Telemetry"),
                                "fields", List.of(
                                        Map.of("name", "deviceId", "type", "string", "required", true),
                                        Map.of("name", "ts", "type", "date", "required", true),
                                        Map.of("name", "temperature", "type", "number")))),
                "blocks", List.of(Map.of(
                        "id", "readTemp",
                        "type", "external_call",
                        "params", Map.of(
                                "url", "https://ot-gateway.test/temp",
                                "credentialRef", "gateway-key",
                                "egress", Map.of("allowedHosts", List.of("ot-gateway.test")),
                                "retry", Map.of("maxAttempts", 1),
                                "sink", Map.of(
                                        "type", "record",
                                        "entity", "telemetry",
                                        "mapping", Map.of(
                                                "deviceId", "/device",
                                                "ts", "/ts",
                                                "temperature", "/temp"),
                                        "dedupeKey", List.of("deviceId", "ts"))))),
                "ui", Map.of("views", List.of("registry:devices", "registry:telemetry")));
    }

    /** Audit rows persist AFTER_COMMIT on an executor thread; poll briefly. */
    private void awaitAuditRow(String action, String metadataFragment) {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            Long count = jdbc.queryForObject(
                    "select count(*) from audit_logs where action = ? and tenant_id = ? "
                            + "and metadata::text like ?",
                    Long.class, action, workspaceId, "%" + metadataFragment + "%");
            if (count != null && count > 0) {
                assertNotNull(count);
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
