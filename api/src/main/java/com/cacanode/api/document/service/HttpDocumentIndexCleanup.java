package com.cacanode.api.document.service;

import java.util.UUID;
import com.cacanode.api.ai.api.AiInferenceApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpDocumentIndexCleanup implements DocumentIndexCleanup {
    private final AiInferenceApi inferenceClient;

    @Override
    public void delete(UUID tenantId, UUID knowledgeBaseId, UUID documentId) {
        inferenceClient.deleteDocumentIndex(
                tenantId, knowledgeBaseId, documentId, UUID.randomUUID().toString());
    }
}
