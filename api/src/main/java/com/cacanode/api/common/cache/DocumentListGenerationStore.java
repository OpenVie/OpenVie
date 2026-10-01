package com.cacanode.api.common.cache;

import java.util.OptionalLong;
import java.util.UUID;

public interface DocumentListGenerationStore {
    OptionalLong current(UUID tenantId, UUID knowledgeBaseId);
    CacheOperationStatus increment(UUID tenantId, UUID knowledgeBaseId);
}
