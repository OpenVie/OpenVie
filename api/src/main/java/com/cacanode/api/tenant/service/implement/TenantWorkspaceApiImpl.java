package com.cacanode.api.tenant.service.implement;

import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.tenant.api.TenantWorkspaceApi;
import com.cacanode.api.tenant.enums.ChatbotStatus;
import com.cacanode.api.tenant.enums.KnowledgeBaseStatus;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.repository.ChatbotRepository;
import com.cacanode.api.tenant.repository.KnowledgeBaseRepository;
import com.cacanode.api.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantWorkspaceApiImpl implements TenantWorkspaceApi {
    private final TenantRepository tenantRepository;
    private final ChatbotRepository chatbotRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.cacanode.api.tenant.service.KnowledgeBaseRevisionService revisionService;

    @Override
    @Transactional(readOnly = true)
    public WorkspaceContext requireActiveWorkspace(UUID tenantId, UUID chatbotId, UUID knowledgeBaseId) {
        var tenant = activeTenant(tenantId);
        var chatbot = chatbotRepository.findByIdAndTenant_IdAndKnowledgeBase_IdAndStatus(
                        chatbotId, tenantId, knowledgeBaseId, ChatbotStatus.ACTIVE)
                .orElseThrow(this::notFound);
        var knowledgeBase = chatbot.getKnowledgeBase();
        if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw notFound();
        }
        return new WorkspaceContext(tenantId, chatbotId, knowledgeBaseId, tenant.getName(),
                knowledgeBase.getSearchRevision());
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceContext requireActiveKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        var tenant = activeTenant(tenantId);
        var knowledgeBase = knowledgeBaseRepository.findByIdAndTenantId(knowledgeBaseId, tenantId)
                .filter(item -> item.getStatus() == KnowledgeBaseStatus.ACTIVE)
                .orElseThrow(this::notFound);
        return new WorkspaceContext(tenantId, null, knowledgeBaseId, tenant.getName(),
                knowledgeBase.getSearchRevision());
    }

    @Override
    public void incrementSearchRevision(UUID tenantId, UUID knowledgeBaseId) {
        revisionService.increment(tenantId, knowledgeBaseId);
    }

    private com.cacanode.api.tenant.model.Tenant activeTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
                .orElseThrow(this::notFound);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Tenant workspace was not found");
    }
}
