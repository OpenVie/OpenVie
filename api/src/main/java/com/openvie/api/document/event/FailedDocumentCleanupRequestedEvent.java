package com.openvie.api.document.event;

import java.util.UUID;

public record FailedDocumentCleanupRequestedEvent(
        UUID tenantId,
        UUID knowledgeBaseId,
        UUID documentId,
        String storagePath
) {
}
