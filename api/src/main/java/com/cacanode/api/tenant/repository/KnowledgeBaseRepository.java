package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
    Optional<KnowledgeBase> findByTenantIdAndSlug(UUID tenantId, String slug);
    Optional<KnowledgeBase> findByIdAndTenantId(UUID id, UUID tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KnowledgeBase knowledgeBase
            set knowledgeBase.searchRevision = knowledgeBase.searchRevision + 1
            where knowledgeBase.id = :knowledgeBaseId
              and knowledgeBase.tenant.id = :tenantId
            """)
    int incrementSearchRevision(
            @Param("tenantId") UUID tenantId,
            @Param("knowledgeBaseId") UUID knowledgeBaseId
    );
}
