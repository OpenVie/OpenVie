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
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.service.BizAppReportResolver;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.document.api.DocumentKind;
import com.openvie.api.tenant.api.TenantKnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectionConcurrencyTest {

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

        projector = new BizAppKnowledgeProjector(
                documentApi,
                tenantKnowledgeApi,
                storageRouter,
                reportResolver
        );
    }

    private Manifest createManifest(String kind, String entityName) {
        Entity entity = new Entity(
                entityName,
                new Localized(entityName, entityName),
                List.of(new Field("name", "string", false, 100, null, null)),
                new KnowledgeConfig(true, "name", List.of("name")),
                null
        );
        return new Manifest(
                "bizapp.v1",
                kind,
                new Localized(kind, kind),
                1,
                "icon",
                Map.of("read", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER")),
                List.of(entity),
                List.of(),
                new Ui(List.of())
        );
    }

    @Test
    void flatNamingEnsuresNoCollisionBetweenApps() {
        BizAppInstallation crmInstall = new BizAppInstallation();
        crmInstall.setKind("crm");

        BizAppInstallation bookingInstall = new BizAppInstallation();
        bookingInstall.setKind("booking");

        UUID recordId = UUID.randomUUID();
        BizAppRecord crmRecord = new BizAppRecord();
        crmRecord.setId(recordId);
        crmRecord.setAppKind("crm");
        crmRecord.setEntity("lead");
        crmRecord.setPayload(Map.of("name", "John"));

        BizAppRecord bookingRecord = new BizAppRecord();
        bookingRecord.setId(recordId);
        bookingRecord.setAppKind("booking");
        bookingRecord.setEntity("lead");
        bookingRecord.setPayload(Map.of("name", "Jane"));

        projector.projectRecord(tenantId, actorId, crmInstall, createManifest("crm", "lead"), crmRecord);
        projector.projectRecord(tenantId, actorId, bookingInstall, createManifest("booking", "lead"), bookingRecord);

        ArgumentCaptor<String> fileCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentApi, times(2)).ingestText(
                eq(tenantId), eq(actorId), eq(kbId), fileCaptor.capture(), anyString(), eq(DocumentKind.BIZAPP_RECORD), anyString()
        );

        List<String> fileNames = fileCaptor.getAllValues();
        assertEquals(2, fileNames.size());
        assertEquals("bizapp__crm__lead__" + recordId + ".md", fileNames.get(0));
        assertEquals("bizapp__booking__lead__" + recordId + ".md", fileNames.get(1));
        assertNotEquals(fileNames.get(0), fileNames.get(1), "Flat filenames must never collide between apps");
    }

    @Test
    void coalescingDoesNotThrowWhenProjectingRapidUpdates() {
        BizAppInstallation crmInstall = new BizAppInstallation();
        crmInstall.setKind("crm");

        UUID recordId = UUID.randomUUID();
        BizAppRecord crmRecord = new BizAppRecord();
        crmRecord.setId(recordId);
        crmRecord.setAppKind("crm");
        crmRecord.setEntity("lead");
        crmRecord.setPayload(Map.of("name", "John V1"));

        Manifest manifest = createManifest("crm", "lead");

        assertDoesNotThrow(() -> {
            projector.projectRecord(tenantId, actorId, crmInstall, manifest, crmRecord);
            crmRecord.setPayload(Map.of("name", "John V2"));
            projector.projectRecord(tenantId, actorId, crmInstall, manifest, crmRecord);
        });

        verify(documentApi, times(2)).ingestText(
                eq(tenantId), eq(actorId), eq(kbId), eq("bizapp__crm__lead__" + recordId + ".md"),
                anyString(), eq(DocumentKind.BIZAPP_RECORD), eq("bizapp:crm")
        );
    }
}
