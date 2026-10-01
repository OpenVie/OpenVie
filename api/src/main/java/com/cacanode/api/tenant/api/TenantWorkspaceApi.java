package com.cacanode.api.tenant.api;

import java.util.UUID;

public interface TenantWorkspaceApi {
    WorkspaceContext requireActiveWorkspace(UUID tenantId, UUID chatbotId, UUID knowledgeBaseId);

    WorkspaceContext requireActiveKnowledgeBase(UUID tenantId, UUID knowledgeBaseId);

    void incrementSearchRevision(UUID tenantId, UUID knowledgeBaseId);

    record WorkspaceContext(
            UUID tenantId,
            UUID chatbotId,
            UUID knowledgeBaseId,
            String tenantName,
            long searchRevision
    ) {
    }
}
