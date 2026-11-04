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
import com.openvie.api.document.api.DocumentKind;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.model.Document;
import com.openvie.api.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemDocumentScopeRejectionTest {

    private DocumentRepository repository;
    private DocumentService documentService;
    private DocumentApiImpl documentApi;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID kbId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        documentService = mock(DocumentService.class);
        documentApi = new DocumentApiImpl(repository, documentService);
    }

    @Test
    void validateChatScopeRejectsSystemDocumentIds() {
        UUID sysDocId = UUID.randomUUID();
        Document sysDoc = new Document();
        sysDoc.setId(sysDocId);
        sysDoc.setTenantId(tenantId);
        sysDoc.setKnowledgeBaseId(kbId);
        sysDoc.setStatus(DocumentStatus.COMPLETED);
        sysDoc.setDocumentKind(DocumentKind.BIZAPP_RECORD);
        sysDoc.setSourceRef("bizapp:crm");

        when(repository.findByIdInAndTenantIdAndKnowledgeBaseIdAndStatus(
                List.of(sysDocId), tenantId, kbId, DocumentStatus.COMPLETED
        )).thenReturn(List.of(sysDoc));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                documentApi.validateChatScope(tenantId, kbId, List.of(sysDocId)));

        assertTrue(ex.getMessage().contains("system documents"));
    }

    @Test
    void validateChatScopeAcceptsUserDocumentIds() {
        UUID userDocId = UUID.randomUUID();
        Document userDoc = new Document();
        userDoc.setId(userDocId);
        userDoc.setTenantId(tenantId);
        userDoc.setKnowledgeBaseId(kbId);
        userDoc.setStatus(DocumentStatus.COMPLETED);
        userDoc.setDocumentKind(DocumentKind.USER);

        when(repository.findByIdInAndTenantIdAndKnowledgeBaseIdAndStatus(
                List.of(userDocId), tenantId, kbId, DocumentStatus.COMPLETED
        )).thenReturn(List.of(userDoc));

        assertDoesNotThrow(() ->
                documentApi.validateChatScope(tenantId, kbId, List.of(userDocId)));
    }
}
