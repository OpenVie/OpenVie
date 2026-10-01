package com.cacanode.api.common.cache;

import java.util.UUID;

public record DocumentListInvalidationEvent(UUID tenantId, UUID knowledgeBaseId) {
    public DocumentListInvalidationEvent {
        if (tenantId == null || knowledgeBaseId == null) {
            throw new IllegalArgumentException("Trusted tenant and knowledge-base IDs are required");
        }
    }
}
