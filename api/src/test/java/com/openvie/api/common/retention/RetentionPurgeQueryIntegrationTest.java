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

package com.openvie.api.common.retention;

import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.testsupport.PostgresTestContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The purge query is the only thing between legal hold and an irreversible
 * delete, so it is exercised against real PostgreSQL: only records in an
 * ACTIVE, non-held workspace may become purge candidates.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@ActiveProfiles("test")
class RetentionPurgeQueryIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_retention");
    private static final AtomicInteger SUFFIX = new AtomicInteger();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private BizAppRecordRepository records;

    @Test
    void excludesLegalHoldAndArchivedWorkspaces() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        Set<UUID> candidates = transactions.execute(status -> {
            String suffix = String.valueOf(SUFFIX.incrementAndGet());

            Organization organization = new Organization();
            organization.setName("Retention Org " + suffix);
            organization.setSlug("retention-org-" + suffix);
            em.persist(organization);
            em.flush();

            User owner = new User();
            owner.setOrganization(organization);
            owner.setEmail("retention-" + suffix + "@example.com");
            owner.setPasswordHash("{noop}not-used-in-this-suite");
            owner.setFullName("Retention Owner");
            owner.setRole(OrgRole.ORG_OWNER);
            owner.setStatus(UserStatus.ACTIVE);
            em.persist(owner);
            em.flush();

            UUID activeRecord = record(organization, owner, "active-" + suffix,
                    TenantStatus.ACTIVE, false);
            record(organization, owner, "held-" + suffix, TenantStatus.ACTIVE, true);
            record(organization, owner, "archived-ws-" + suffix, TenantStatus.ARCHIVED, false);

            em.flush();
            return Set.of(activeRecord);
        });

        List<BizAppRecord> purgeCandidates = records.findPurgeCandidates(cutoff, 100);

        assertEquals(candidates,
                purgeCandidates.stream().map(BizAppRecord::getId).collect(Collectors.toSet()));
    }

    private UUID record(Organization organization, User owner, String slug,
            TenantStatus workspaceStatus, boolean legalHold) {
        Tenant workspace = new Tenant();
        workspace.setOrganization(organization);
        workspace.setName("Workspace " + slug);
        workspace.setSlug(slug);
        workspace.setStatus(workspaceStatus);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        workspace.setLegalHold(legalHold);
        em.persist(workspace);
        em.flush();

        BizAppRecord record = new BizAppRecord();
        record.setOrgId(organization.getId());
        record.setTenantId(workspace.getId());
        record.setAppKind("crm");
        record.setEntity("leads");
        record.setPayload(Map.of("name", slug));
        record.setRuntime(Map.of());
        record.setStatus("ARCHIVED");
        record.setArchivedAt(LocalDateTime.now().minusDays(45));
        record.setCreatedBy(owner.getId());
        em.persist(record);
        em.flush();
        return record.getId();
    }
}
