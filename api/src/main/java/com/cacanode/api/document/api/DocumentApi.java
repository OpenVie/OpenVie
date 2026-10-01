package com.cacanode.api.document.api;

import java.util.List;
import java.util.UUID;

public interface DocumentApi {
    void validateCitations(
            UUID tenantId, UUID knowledgeBaseId, List<UUID> documentIds);
}
