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

package com.openvie.api.tenant.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.openvie.api.tenant.enums.KnowledgeBaseStatus;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.KnowledgeBase;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;

/**
 * Proves the search-revision increment is atomic and workspace-scoped: a
 * revision bump for one workspace must never touch another workspace's
 * knowledge base, and a rolled-back bump must disappear.
 */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeBaseRevisionRepositoryIntegrationTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void incrementIsAtomicTenantScopedAndRollsBackWithTransaction() {
        Organization organization = organization("revision-org");
        Tenant tenant = tenant(organization, "revision-main");
        Tenant otherTenant = tenant(organization, "revision-other");
        KnowledgeBase knowledgeBase = knowledgeBase(tenant, "revision-kb");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertEquals(0, knowledgeBase.getSearchRevision());
        assertEquals(Integer.valueOf(0), transaction.execute(status ->
                knowledgeBaseRepository.incrementSearchRevision(
                        otherTenant.getId(), knowledgeBase.getId())));
        assertEquals(Integer.valueOf(1), transaction.execute(status ->
                knowledgeBaseRepository.incrementSearchRevision(
                        tenant.getId(), knowledgeBase.getId())));
        assertEquals(1, knowledgeBaseRepository.findById(knowledgeBase.getId())
                .orElseThrow().getSearchRevision());

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            knowledgeBaseRepository.incrementSearchRevision(tenant.getId(), knowledgeBase.getId());
            throw new IllegalStateException("rollback");
        }));

        assertEquals(1, knowledgeBaseRepository.findById(knowledgeBase.getId())
                .orElseThrow().getSearchRevision());
    }

    private Organization organization(String slug) {
        Organization value = new Organization();
        value.setName(slug);
        value.setSlug(slug);
        return organizationRepository.save(value);
    }

    private Tenant tenant(Organization organization, String slug) {
        Tenant value = new Tenant();
        value.setOrganization(organization);
        value.setName(slug);
        value.setSlug(slug);
        value.setStatus(TenantStatus.ACTIVE);
        value.setVisibility(WorkspaceVisibility.PUBLIC);
        return tenantRepository.save(value);
    }

    private KnowledgeBase knowledgeBase(Tenant tenant, String slug) {
        KnowledgeBase value = new KnowledgeBase();
        value.setTenant(tenant);
        value.setName(slug);
        value.setSlug(slug);
        value.setDefaultLocale("vi-VN");
        value.setStatus(KnowledgeBaseStatus.ACTIVE);
        return knowledgeBaseRepository.save(value);
    }
}
