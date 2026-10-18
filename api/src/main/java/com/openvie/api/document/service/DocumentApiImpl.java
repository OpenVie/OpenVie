package com.openvie.api.document.service;

import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.model.Document;
import com.openvie.api.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentApiImpl implements DocumentApi {
    private final DocumentRepository repository;

    // Authoritative citation check: every cited document must be a COMPLETED
    // document of the same tenant and knowledge base. Never weakened for compilation.
    @Override
    @Transactional(readOnly = true)
    public void validateCitations(
            UUID tenantId, UUID knowledgeBaseId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        List<Document> documents = repository.findByIdInAndTenantIdAndKnowledgeBaseIdAndStatus(
                documentIds, tenantId, knowledgeBaseId, DocumentStatus.COMPLETED);
        if (documents.size() != documentIds.size()) {
            throw new BadRequestException("The model returned an invalid citation.");
        }
    }
}
