package com.cacanode.api.tenant.repository;

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

import com.cacanode.api.tenant.enums.KnowledgeBaseStatus;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.model.KnowledgeBase;
import com.cacanode.api.tenant.model.Tenant;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class KnowledgeBaseRevisionRepositoryIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void incrementIsAtomicTenantScopedAndRollsBackWithTransaction() {
        Tenant tenant = tenant("revision-main");
        Tenant otherTenant = tenant("revision-other");
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

    private Tenant tenant(String slug) {
        Tenant value = new Tenant();
        value.setName(slug);
        value.setSlug(slug);
        value.setStatus(TenantStatus.ACTIVE);
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
