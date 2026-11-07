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

import com.openvie.api.bizapp.dto.BizAppDtos.InstallResult;
import com.openvie.api.bizapp.dto.BizAppDtos.RecordView;
import com.openvie.api.bizapp.service.BizAppInstallService;
import com.openvie.api.bizapp.service.BizAppMarketplaceService;
import com.openvie.api.bizapp.service.BizAppRecordService;
import com.openvie.api.bizapp.service.BizAppReportResolver;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ConflictException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tier 3 installable-app contract exercised against real Postgres with the
 * full migration chain (record store, marketplace, report views): a platform
 * template installs into a workspace, records round-trip the JSONB store with
 * manifest-validated payloads, report SQL reads only live rows, and uninstall
 * detaches the app while keeping its data.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@ActiveProfiles("test")
class BizAppInstallFlowIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_bizapps");

    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private BizAppMarketplaceService marketplaceService;

    @Autowired
    private BizAppInstallService installService;

    @Autowired
    private BizAppRecordService recordService;

    @Autowired
    private BizAppReportResolver reportResolver;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    private UUID orgId;
    private UUID workspaceId;
    private UUID ownerId;

    @BeforeEach
    void seed() {
        transactions.executeWithoutResult(status -> {
            String suffix = String.valueOf(SUFFIX.incrementAndGet());

            Organization organization = new Organization();
            organization.setName("BizApp Org " + suffix);
            organization.setSlug("bizapps-" + suffix);
            em.persist(organization);
            em.flush();
            orgId = organization.getId();

            Tenant workspace = new Tenant();
            workspace.setOrganization(organization);
            workspace.setName("BizApp Workspace");
            workspace.setSlug("bizapps-ws-" + suffix);
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            workspaceId = workspace.getId();

            User owner = new User();
            owner.setOrganization(organization);
            owner.setEmail("owner-" + suffix + "@bizapps.example");
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

    @Test
    void platformTemplateInstallsAndTheRecordLifecycleSurvivesRealPostgres() {
        InstallResult installed = installCrm();
        assertEquals("crm", installed.kind(), "the bundled template installs under its own kind");
        assertEquals(1, installed.version());

        Map<String, Object> lead = new HashMap<>();
        lead.put("name", "Bao Motorcycles");
        lead.put("email", "bao@motor.example");
        lead.put("stage", "NEW");
        lead.put("dealValue", 1200.5);
        RecordView created = recordService.create(workspaceId, ownerId, "ORG_OWNER", "crm", "leads", lead);
        assertNotNull(created.id());
        assertEquals("crm", created.appKind());
        assertEquals("leads", created.entity());
        assertEquals("ACTIVE", created.status());
        assertEquals("Bao Motorcycles", created.payload().get("name"));
        assertEquals("NEW", created.payload().get("stage"));
        assertNotNull(((Number) created.payload().get("dealValue")), "numbers survive the JSONB round-trip");
        assertNotNull(created.runtime(), "runtime is present and separate from user payload");
        assertTrue(created.runtime().isEmpty());

        // Sales progress: stage flips while untouched fields stay in place.
        RecordView updated = recordService.update(workspaceId, ownerId, "ORG_OWNER", "crm", "leads",
                created.id(), Map.of("stage", "WON"));
        assertEquals("WON", updated.payload().get("stage"));
        assertEquals("Bao Motorcycles", updated.payload().get("name"));

        RecordView second = recordService.create(workspaceId, ownerId, "ORG_OWNER", "crm", "leads",
                Map.of("name", "Delta Foods", "stage", "CONTACTED"));
        recordService.archive(workspaceId, ownerId, "ORG_OWNER", "crm", "leads", second.id());

        var onlyActive = recordService.list(workspaceId, "crm", "leads", false, 0, 20, "ORG_OWNER");
        assertEquals(1, onlyActive.getTotalElements(), "archived rows stay out of the registry");
        var withArchived = recordService.list(workspaceId, "crm", "leads", true, 0, 20, "ORG_OWNER");
        assertEquals(2, withArchived.getTotalElements());
        assertEquals("ARCHIVED", recordService.get(workspaceId, "crm", "leads", second.id(), "ORG_OWNER").status());

        var rows = reportResolver.run(workspaceId, "crm", "pipeline", Map.of());
        assertEquals(1, rows.size(), "report SQL hides archived rows exactly like the registry");
        assertEquals("Bao Motorcycles", rows.get(0).get("name"));
        assertEquals("WON", rows.get(0).get("stage"));

        installService.uninstall(workspaceId, ownerId, "crm");
        assertTrue(installService.listInstalled(workspaceId).isEmpty(), "uninstall detaches the app");
        assertThrows(ResourceNotFoundException.class, () -> installService.getInstalled(workspaceId, "crm"));
        // Records are kept but the runtime surface is closed.
        assertThrows(ResourceNotFoundException.class,
                () -> recordService.update(workspaceId, ownerId, "ORG_OWNER", "crm", "leads",
                        created.id(), Map.of("stage", "LOST")));
    }

    @Test
    void manifestValidationRejectsUnknownFieldsBadEnumsAndUninstalledKinds() {
        installCrm();

        Map<String, Object> ghost = new HashMap<>();
        ghost.put("name", "Ghost Lead");
        ghost.put("stage", "NEW");
        ghost.put("discountRatio", 0.3);
        assertThrows(BadRequestException.class,
                () -> recordService.create(workspaceId, ownerId, "ORG_OWNER", "crm", "leads", ghost),
                "fields outside the manifest schema must not reach the store");

        Map<String, Object> badEnum = new HashMap<>();
        badEnum.put("name", "Broken Lead");
        badEnum.put("stage", "SQUEEZED");
        assertThrows(BadRequestException.class,
                () -> recordService.create(workspaceId, ownerId, "ORG_OWNER", "crm", "leads", badEnum),
                "enum fields accept only declared options");

        assertThrows(ResourceNotFoundException.class,
                () -> recordService.create(workspaceId, ownerId, "ORG_OWNER", "booking", "bookings",
                        Map.of("title", "Ghost App")),
                "kinds without an installation are unreachable");

        assertTrue(installService.listInstalled(workspaceId).stream()
                .noneMatch(app -> "booking".equals(app.kind())));
    }

    @Test
    void appCanBeReinstalledAfterBeingUninstalled() {
        installCrm();
        installService.uninstall(workspaceId, ownerId, "crm");
        assertTrue(installService.listInstalled(workspaceId).isEmpty());

        InstallResult reinstalled = installCrm();
        assertNotNull(reinstalled);
        assertEquals("crm", reinstalled.kind());
        assertEquals(1, installService.listInstalled(workspaceId).size());
    }

    @Test
    void manifestUpgradeKeepsRecordsAndRejectsIncompatibleManifests() {
        Map<String, Object> v1 = notesManifest(1, List.of(stringField("title")));
        installService.install(workspaceId, orgId, ownerId, v1);
        RecordView created = recordService.create(workspaceId, ownerId, "ORG_OWNER", "notes", "notes",
                Map.of("title", "first note"));

        // v2 adds a field: existing rows survive untouched.
        Map<String, Object> v2 = notesManifest(2, List.of(stringField("title"), stringField("body")));
        InstallResult upgraded = installService.upgrade(workspaceId, orgId, ownerId, "notes", v2);
        assertEquals(2, upgraded.version());
        assertEquals(2, installService.getInstalled(workspaceId, "notes").version());
        assertEquals(1, recordService.list(workspaceId, "notes", "notes", false, 0, 20, "ORG_OWNER")
                .getTotalElements(), "upgrade must not delete records");
        assertEquals("first note", recordService.get(workspaceId, "notes", "notes", created.id(), "ORG_OWNER")
                .payload().get("title"));

        // Removing a field would orphan stored values.
        Map<String, Object> removal = notesManifest(3, List.of(stringField("title")));
        assertThrows(BadRequestException.class,
                () -> installService.upgrade(workspaceId, orgId, ownerId, "notes", removal));
        // Retyping a field would corrupt stored values.
        Map<String, Object> retype = notesManifest(3, List.of(
                Map.of("name", "title", "type", "number"),
                stringField("body")));
        assertThrows(BadRequestException.class,
                () -> installService.upgrade(workspaceId, orgId, ownerId, "notes", retype));
        // Versions must move forward.
        assertThrows(ConflictException.class,
                () -> installService.upgrade(workspaceId, orgId, ownerId, "notes", v2));
    }

    private Map<String, Object> notesManifest(int version, List<Map<String, Object>> fields) {
        return Map.of(
                "schema", "bizapp.v1",
                "kind", "notes",
                "name", Map.of("en", "Notes", "vi", "Ghi chú"),
                "version", version,
                "storage", "jsonb",
                "rbac", Map.of(
                        "read", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"),
                        "write", List.of("ORG_OWNER", "MEMBER")),
                "entities", List.of(Map.of(
                        "name", "notes",
                        "label", Map.of("en", "Notes", "vi", "Ghi chú"),
                        "fields", fields)));
    }

    private Map<String, Object> stringField(String name) {
        return Map.of("name", name, "type", "string");
    }

    private InstallResult installCrm() {
        Map<String, Object> manifest = marketplaceService.templateManifest(orgId, "platform:crm");
        assertEquals("crm", manifest.get("kind"), "the platform ref resolves the bundled manifest");
        return installService.install(workspaceId, orgId, ownerId, manifest);
    }
}
