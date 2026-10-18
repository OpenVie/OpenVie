package com.openvie.api.tenant.repository;

import com.openvie.api.tenant.model.Chatbot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.openvie.api.tenant.enums.ChatbotStatus;

public interface ChatbotRepository extends JpaRepository<Chatbot, UUID> {
    List<Chatbot> findByTenantId(UUID tenantId);

    Optional<Chatbot> findFirstByTenant_IdAndKnowledgeBase_IdAndStatusOrderByCreatedAtAsc(
            UUID tenantId,
            UUID knowledgeBaseId,
            com.openvie.api.tenant.enums.ChatbotStatus status
    );

    Optional<Chatbot> findByIdAndTenant_IdAndKnowledgeBase_IdAndStatus(
            UUID id, UUID tenantId, UUID knowledgeBaseId, ChatbotStatus status);
}
