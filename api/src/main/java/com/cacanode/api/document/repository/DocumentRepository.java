package com.cacanode.api.document.repository;

import com.cacanode.api.document.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.cacanode.api.document.enums.DocumentStatus;
import org.springframework.data.domain.Pageable;

public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {
    Optional<Document> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Document> findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(
            UUID tenantId,
            UUID knowledgeBaseId
    );

    List<Document> findByTenantIdAndKnowledgeBaseIdAndStatus(
            UUID tenantId, UUID knowledgeBaseId, DocumentStatus status);


    List<Document> findByIdInAndTenantIdAndKnowledgeBaseIdAndStatus(
            List<UUID> ids, UUID tenantId, UUID knowledgeBaseId, DocumentStatus status);

    List<Document> findByStatusOrderByIdAsc(DocumentStatus status, Pageable pageable);

    List<Document> findByStatusAndIdGreaterThanOrderByIdAsc(
            DocumentStatus status, UUID afterId, Pageable pageable);
}
