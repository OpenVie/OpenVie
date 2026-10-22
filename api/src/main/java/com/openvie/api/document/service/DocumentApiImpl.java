// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.document.service;

import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.ApprovalStatus;
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
    private final DocumentService documentService;

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

    @Override
    @Transactional(readOnly = true)
    public void validateChatScope(
            UUID tenantId, UUID knowledgeBaseId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        List<Document> documents = repository.findByIdInAndTenantIdAndKnowledgeBaseIdAndStatus(
                documentIds, tenantId, knowledgeBaseId, DocumentStatus.COMPLETED);
        if (documents.size() != documentIds.size()) {
            throw new BadRequestException(
                    "Chat scope must reference completed documents of this knowledge base.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalDocumentSnapshot requireApprovalCandidate(UUID tenantId, UUID documentId) {
        Document document = repository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return new ApprovalDocumentSnapshot(
                document.getId(), document.getTenantId(), document.getKnowledgeBaseId(), document.getFileName());
    }

    @Override
    public void markApprovalPending(UUID tenantId, UUID documentId, String publishTarget) {
        documentService.markApprovalPending(tenantId, documentId, publishTarget);
    }

    @Override
    public void recordApprovalDecision(UUID tenantId, UUID documentId, ApprovalStatus decision, UUID decidedBy) {
        documentService.recordApprovalDecision(tenantId, documentId, decision, decidedBy);
    }
}
