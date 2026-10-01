package com.cacanode.api.document.service;

import java.util.UUID;

public interface DocumentIndexCleanup {
    void delete(UUID tenantId, UUID knowledgeBaseId, UUID documentId);
}
