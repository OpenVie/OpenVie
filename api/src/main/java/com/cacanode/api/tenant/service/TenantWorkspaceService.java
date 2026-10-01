package com.cacanode.api.tenant.service;

import com.cacanode.api.ai.api.ModelConfigurationApi;
import com.cacanode.api.common.cache.BusinessCache;
import com.cacanode.api.common.cache.CacheKeyFactory;
import com.cacanode.api.common.cache.VersionedJsonCache;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.tenant.dto.TenantWorkspaceResponse;
import com.cacanode.api.tenant.enums.ChatbotStatus;
import com.cacanode.api.tenant.enums.KnowledgeBaseStatus;
import com.cacanode.api.tenant.model.Chatbot;
import com.cacanode.api.tenant.model.KnowledgeBase;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.repository.ChatbotRepository;
import com.cacanode.api.tenant.repository.KnowledgeBaseRepository;
import com.cacanode.api.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Creates and reads the knowledge base plus assistant that every workspace
 * needs before it can ingest or answer.
 */
@Service
@RequiredArgsConstructor
public class TenantWorkspaceService {
    private static final String DEFAULT_KNOWLEDGE_BASE_SLUG = "default";
    private static final String DEFAULT_LOCALE = "vi-VN";
    private static final String DEFAULT_KNOWLEDGE_BASE_NAME = "Default Knowledge Base";
    private static final String DEFAULT_CHATBOT_NAME = "OpenVie Assistant";
    private static final String DEFAULT_WELCOME_MESSAGE = "Xin chao! Toi co the giup gi cho ban?";

    private final TenantRepository tenantRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ChatbotRepository chatbotRepository;
    private final ModelConfigurationApi modelConfigurationApi;
    @Autowired(required = false)
    private VersionedJsonCache businessCache;
    @Autowired(required = false)
    private CacheKeyFactory cacheKeyFactory;

    @Transactional
    public TenantWorkspaceResponse getOrProvisionWorkspace(UUID tenantId) {
        if (businessCache == null || cacheKeyFactory == null) {
            return loadOrProvisionAuthoritative(tenantId);
        }
        return businessCache.getOrLoad(
                BusinessCache.WORKSPACE,
                cacheKeyFactory.build("workspace", "tenant", tenantId.toString()),
                TenantWorkspaceResponse.class,
                () -> loadOrProvisionAuthoritative(tenantId)
        );
    }

    private TenantWorkspaceResponse loadOrProvisionAuthoritative(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace was not found"));

        KnowledgeBase knowledgeBase = getOrCreateKnowledgeBase(tenant);
        Chatbot chatbot = getOrCreateChatbot(tenant, knowledgeBase);

        return toResponse(tenant.getId(), knowledgeBase, chatbot);
    }

    /** Idempotently creates the KB + assistant rows for a new workspace. */
    @Transactional
    public void provisionWorkspaceAssets(Tenant tenant) {
        KnowledgeBase knowledgeBase = getOrCreateKnowledgeBase(tenant);
        getOrCreateChatbot(tenant, knowledgeBase);
    }

    private KnowledgeBase getOrCreateKnowledgeBase(Tenant tenant) {
        return knowledgeBaseRepository.findByTenantIdAndSlug(tenant.getId(), DEFAULT_KNOWLEDGE_BASE_SLUG)
                .map(knowledgeBase -> {
                    if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
                        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
                    }
                    return knowledgeBase;
                })
                .orElseGet(() -> {
                    KnowledgeBase knowledgeBase = new KnowledgeBase();
                    knowledgeBase.setTenant(tenant);
                    knowledgeBase.setName(DEFAULT_KNOWLEDGE_BASE_NAME);
                    knowledgeBase.setSlug(DEFAULT_KNOWLEDGE_BASE_SLUG);
                    knowledgeBase.setDescription("Default workspace-scoped knowledge base.");
                    knowledgeBase.setDefaultLocale(DEFAULT_LOCALE);
                    knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
                    return knowledgeBaseRepository.save(knowledgeBase);
                });
    }

    private Chatbot getOrCreateChatbot(Tenant tenant, KnowledgeBase knowledgeBase) {
        return chatbotRepository
                .findFirstByTenant_IdAndKnowledgeBase_IdAndStatusOrderByCreatedAtAsc(
                        tenant.getId(),
                        knowledgeBase.getId(),
                        ChatbotStatus.ACTIVE
                )
                .orElseGet(() -> {
                    Chatbot chatbot = new Chatbot();
                    chatbot.setTenant(tenant);
                    chatbot.setKnowledgeBase(knowledgeBase);
                    chatbot.setModelConfigVersionId(modelConfigurationApi.activeModelConfigurationId());
                    chatbot.setDisplayName(DEFAULT_CHATBOT_NAME);
                    chatbot.setDefaultLocale(knowledgeBase.getDefaultLocale());
                    chatbot.setWelcomeMessage(DEFAULT_WELCOME_MESSAGE);
                    chatbot.setSafeInstructions(
                            "Answer only from this workspace's uploaded documents, cite every "
                            + "factual claim with its source ID, and say when the documents do not "
                            + "contain the answer. Never present another workspace's data as your own.");
                    chatbot.setRetrievalSettings(Map.of(
                            "topK", 8,
                            "graphDepth", 2,
                            "rerank", true,
                            "minScore", 0.35
                    ));
                    chatbot.setStatus(ChatbotStatus.ACTIVE);
                    return chatbotRepository.save(chatbot);
                });
    }

    private TenantWorkspaceResponse toResponse(UUID tenantId, KnowledgeBase knowledgeBase, Chatbot chatbot) {
        return new TenantWorkspaceResponse(
                tenantId,
                new TenantWorkspaceResponse.KnowledgeBaseWorkspace(
                        knowledgeBase.getId(),
                        knowledgeBase.getName(),
                        knowledgeBase.getSlug(),
                        knowledgeBase.getDefaultLocale()
                ),
                new TenantWorkspaceResponse.ChatbotWorkspace(
                        chatbot.getId(),
                        chatbot.getDisplayName(),
                        chatbot.getDefaultLocale(),
                        chatbot.getWelcomeMessage()
                )
        );
    }
}
