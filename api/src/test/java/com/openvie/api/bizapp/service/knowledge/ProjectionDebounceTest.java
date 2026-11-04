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

package com.openvie.api.bizapp.service.knowledge;

import com.openvie.api.bizapp.dto.BizAppManifest.*;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.service.BizAppReportResolver;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentKind;
import com.openvie.api.tenant.api.TenantKnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectionDebounceTest {

    private DocumentApi documentApi;
    private TenantKnowledgeApi tenantKnowledgeApi;
    private BizAppStorageRouter storageRouter;
    private BizAppReportResolver reportResolver;
    private BizAppKnowledgeProjector projector;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID kbId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documentApi = mock(DocumentApi.class);
        tenantKnowledgeApi = mock(TenantKnowledgeApi.class);
        storageRouter = mock(BizAppStorageRouter.class);
        reportResolver = mock(BizAppReportResolver.class);

        when(tenantKnowledgeApi.getOrCreateAppKnowledgeBase(any(), any(), anyBoolean())).thenReturn(kbId);
        when(reportResolver.run(any(), any(), any(), any())).thenReturn(List.of(Map.of("count", 42)));

        projector = new BizAppKnowledgeProjector(
                documentApi,
                tenantKnowledgeApi,
                storageRouter,
                reportResolver
        );
        projector.setDebounceMs(30000); // 30s debounce
    }

    @Test
    void tenConsecutiveDigestRefreshesInDebounceWindowRunOnlyOnceImmediately() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setKind("crm");

        Block reportBlock = new Block("r1", "report", Map.of("views", List.of(Map.of("name", "pipeline"))));
        Manifest manifest = new Manifest(
                "bizapp.v1", "crm", new Localized("CRM", "CRM"), 1, "icon",
                Map.of("read", List.of("ORG_OWNER")),
                List.of(), List.of(reportBlock), new Ui(List.of())
        );

        // 10 rapid calls
        for (int i = 0; i < 10; i++) {
            projector.refreshDigest(tenantId, actorId, installation, manifest);
        }

        // Only 1 execution should have occurred immediately
        verify(documentApi, times(1)).ingestText(
                eq(tenantId), eq(actorId), eq(kbId), eq("bizapp__crm__digest.md"),
                anyString(), eq(DocumentKind.BIZAPP_DIGEST), eq("bizapp:crm")
        );
    }
}
