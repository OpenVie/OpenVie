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

package com.openvie.api.chat.query;

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.dto.DocumentListItemResponse;
import com.openvie.api.document.model.Document;
import com.openvie.api.document.repository.DocumentRepository;
import com.openvie.api.document.service.DocumentApiImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies Task W7.6: Scoped APP_DATA reading with entity-level fallback.
 */
class EntityScopeFallbackTest {

    private DocumentRepository repository;
    private DocumentApiImpl documentApi;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        documentApi = new DocumentApiImpl(repository, null);
    }

    @Test
    void entityLookupFindsEntityScopedDocuments() {
        UUID docId = UUID.randomUUID();
        Document doc = new Document();
        doc.setId(docId);
        doc.setTenantId(tenantId);
        doc.setFileName("bizapp__custom__items__" + docId + ".md");

        when(repository.findByTenantIdAndSourceRef(tenantId, "bizapp:custom:items"))
                .thenReturn(List.of(doc));

        List<UUID> ids = documentApi.listProjectedDocumentIds(tenantId, "custom", "items");
        assertEquals(1, ids.size());
        assertEquals(docId, ids.get(0));
    }

    @Test
    void entityLookupFallsBackToLegacyFileNamePrefixWhenEntitySourceRefEmpty() {
        UUID docId = UUID.randomUUID();
        Document legacyDoc = new Document();
        legacyDoc.setId(docId);
        legacyDoc.setTenantId(tenantId);
        legacyDoc.setFileName("bizapp__custom__items__" + docId + ".md");

        // Entity ref empty
        when(repository.findByTenantIdAndSourceRef(tenantId, "bizapp:custom:items"))
                .thenReturn(List.of());
        // App ref has legacy document
        when(repository.findByTenantIdAndSourceRef(tenantId, "bizapp:custom"))
                .thenReturn(List.of(legacyDoc));

        List<UUID> ids = documentApi.listProjectedDocumentIds(tenantId, "custom", "items");
        assertEquals(1, ids.size());
        assertEquals(docId, ids.get(0), "Must fall back to legacy fileName prefix matching");
    }

    @Test
    void emptyEntityAndAppScopeReturnsEmptyList() {
        when(repository.findByTenantIdAndSourceRef(tenantId, "bizapp:custom:items"))
                .thenReturn(List.of());
        when(repository.findByTenantIdAndSourceRef(tenantId, "bizapp:custom"))
                .thenReturn(List.of());

        List<UUID> ids = documentApi.listProjectedDocumentIds(tenantId, "custom", "items");
        assertTrue(ids.isEmpty());
    }
}
