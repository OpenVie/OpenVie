package com.cacanode.api.tenant.dto;

import java.util.UUID;

public record TenantWorkspaceResponse(
        UUID tenantId,
        KnowledgeBaseWorkspace knowledgeBase,
        ChatbotWorkspace chatbot
) {
    public record KnowledgeBaseWorkspace(
            UUID id,
            String name,
            String slug,
            String defaultLocale
    ) {
    }

    public record ChatbotWorkspace(
            UUID id,
            String displayName,
            String defaultLocale,
            String welcomeMessage
    ) {
    }
}
