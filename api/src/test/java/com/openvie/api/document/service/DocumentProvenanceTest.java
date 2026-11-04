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

import com.openvie.api.common.storage.DocumentStorage;
import com.openvie.api.document.api.DocumentKind;
import com.openvie.api.document.cache.DocumentListCacheKeyFactory;
import com.openvie.api.document.dto.DocumentListItemResponse;
import com.openvie.api.document.messaging.DocumentIngestionPublisher;
import com.openvie.api.document.service.DocumentIndexCleanup;
import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;
import com.openvie.api.document.model.Document;
import com.openvie.api.document.repository.DocumentRepository;
import com.openvie.api.tenant.api.TenantWorkspaceApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentProvenanceTest {

    private DocumentRepository documentRepository;
    private TenantWorkspaceApi tenantWorkspaceApi;
    private DocumentService documentService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID kbId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        tenantWorkspaceApi = mock(TenantWorkspaceApi.class);
        DocumentStorage documentStorage = mock(DocumentStorage.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DocumentListCacheKeyFactory cacheKeyFactory = new DocumentListCacheKeyFactory(new com.openvie.api.common.cache.CacheKeyFactory("ccn"));

        DocumentIngestionPublisher ingestionPublisher = mock(DocumentIngestionPublisher.class);
        DocumentIndexCleanup indexCleanup = mock(DocumentIndexCleanup.class);

        documentService = new DocumentService(
                documentRepository,
                tenantWorkspaceApi,
                documentStorage,
                ingestionPublisher,
                indexCleanup,
                eventPublisher
        );
    }

    @Test
    void newDocumentDefaultsToUserKind() {
        Document doc = new Document();
        assertEquals(DocumentKind.USER, doc.getDocumentKind());
        assertNull(doc.getSourceRef());
    }

    @Test
    void deletingSystemDocumentThrowsAccessDeniedException() {
        UUID docId = UUID.randomUUID();
        Document sysDoc = new Document();
        sysDoc.setId(docId);
        sysDoc.setTenantId(tenantId);
        sysDoc.setUploadedBy(userId);
        sysDoc.setDocumentKind(DocumentKind.BIZAPP_RECORD);
        sysDoc.setSourceRef("bizapp:crm");

        when(documentRepository.findByIdAndTenantId(docId, tenantId)).thenReturn(Optional.of(sysDoc));

        assertThrows(AccessDeniedException.class, () ->
                documentService.delete(tenantId, userId, true, docId));

        verify(documentRepository, never()).delete(any(Document.class));
    }

    @Test
    void defaultListHidesSystemDocuments() {
        Document userDoc = new Document();
        userDoc.setId(UUID.randomUUID());
        userDoc.setTenantId(tenantId);
        userDoc.setKnowledgeBaseId(kbId);
        userDoc.setFileName("user_handbook.pdf");
        userDoc.setFileType(DocumentType.PDF);
        userDoc.setFileSizeBytes(100L);
        userDoc.setStatus(DocumentStatus.COMPLETED);
        userDoc.setJobId(UUID.randomUUID().toString());
        userDoc.setDocumentKind(DocumentKind.USER);
        Document sysDoc = new Document();
        sysDoc.setId(UUID.randomUUID());
        sysDoc.setTenantId(tenantId);
        sysDoc.setKnowledgeBaseId(kbId);
        sysDoc.setFileName("bizapp__crm__digest.md");
        sysDoc.setFileType(DocumentType.MARKDOWN);
        sysDoc.setFileSizeBytes(200L);
        sysDoc.setStatus(DocumentStatus.COMPLETED);
        sysDoc.setJobId(UUID.randomUUID().toString());
        sysDoc.setDocumentKind(DocumentKind.BIZAPP_DIGEST);

        when(documentRepository.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, kbId))
                .thenReturn(List.of(userDoc, sysDoc));

        List<DocumentListItemResponse> result = documentService.list(tenantId, kbId, false);
        assertEquals(1, result.size());
        assertEquals("user_handbook.pdf", result.get(0).fileName());
        assertEquals(DocumentKind.USER, result.get(0).documentKind());
    }
    @Test
    void listWithIncludeSystemTrueReturnsAllDocumentKinds() {
        Document userDoc = new Document();
        userDoc.setId(UUID.randomUUID());
        userDoc.setTenantId(tenantId);
        userDoc.setKnowledgeBaseId(kbId);
        userDoc.setFileName("user_handbook.pdf");
        userDoc.setFileType(DocumentType.PDF);
        userDoc.setFileSizeBytes(100L);
        userDoc.setStatus(DocumentStatus.COMPLETED);
        userDoc.setJobId(UUID.randomUUID().toString());
        userDoc.setDocumentKind(DocumentKind.USER);

        Document sysDoc = new Document();
        sysDoc.setId(UUID.randomUUID());
        sysDoc.setTenantId(tenantId);
        sysDoc.setKnowledgeBaseId(kbId);
        sysDoc.setFileName("bizapp__crm__digest.md");
        sysDoc.setFileType(DocumentType.MARKDOWN);
        sysDoc.setFileSizeBytes(200L);
        sysDoc.setStatus(DocumentStatus.COMPLETED);
        sysDoc.setJobId(UUID.randomUUID().toString());
        sysDoc.setDocumentKind(DocumentKind.BIZAPP_DIGEST);
        sysDoc.setSourceRef("bizapp:crm");

        when(documentRepository.findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, kbId))
                .thenReturn(List.of(userDoc, sysDoc));

        List<DocumentListItemResponse> result = documentService.list(tenantId, kbId, true);
        assertEquals(2, result.size());
        assertEquals("user_handbook.pdf", result.get(0).fileName());
        assertEquals(DocumentKind.USER, result.get(0).documentKind());
        assertEquals("bizapp__crm__digest.md", result.get(1).fileName());
        assertEquals(DocumentKind.BIZAPP_DIGEST, result.get(1).documentKind());

        verify(documentRepository).findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(tenantId, kbId);
    }
}
