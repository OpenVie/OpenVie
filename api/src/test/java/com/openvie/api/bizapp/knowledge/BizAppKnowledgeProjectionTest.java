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

package com.openvie.api.bizapp.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.KnowledgeConfig;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.service.BizAppReportResolver;
import com.openvie.api.bizapp.service.knowledge.BizAppKnowledgeProjector;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.BizAppStorageStore;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.tenant.api.TenantKnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class BizAppKnowledgeProjectionTest {

    private DocumentApi documentApi;
    private TenantKnowledgeApi tenantKnowledgeApi;
    private BizAppStorageRouter storageRouter;
    private BizAppReportResolver reportResolver;
    private BizAppStorageStore storageStore;
    private BizAppKnowledgeProjector projector;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID kbId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documentApi = mock(DocumentApi.class);
        tenantKnowledgeApi = mock(TenantKnowledgeApi.class);
        storageRouter = mock(BizAppStorageRouter.class);
        reportResolver = mock(BizAppReportResolver.class);
        storageStore = mock(BizAppStorageStore.class);

        when(tenantKnowledgeApi.getOrCreateAppKnowledgeBase(any(), any(), anyBoolean())).thenReturn(kbId);
        when(storageRouter.route(any(), any())).thenReturn(storageStore);

        projector = new BizAppKnowledgeProjector(documentApi, tenantKnowledgeApi, storageRouter, reportResolver);
    }

    private Entity projectedEntity() {
        return new Entity(
                "leads",
                new Localized("Leads", "Leads"),
                List.of(
                        new Field("name", "string", true, 200, null, null),
                        new Field("phone", "string", null, false, 40, null, null, true, null), // PII
                        new Field("stage", "enum", null, true, null, null, List.of("NEW", "WON"), false, null)
                ),
                new KnowledgeConfig(true, "name", List.of("name", "phone", "stage")),
                Map.of()
        );
    }

    private Manifest manifest(boolean openRbac) {
        Map<String, List<String>> rbac = openRbac
                ? Map.of("read", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"))
                : Map.of("read", List.of("ORG_OWNER", "WORKSPACE_ADMIN")); // restricted

        return new Manifest(
                "bizapp.v1", "crm", new Localized("CRM", "CRM"), 1, "box",
                rbac, List.of(projectedEntity()), List.of(), null
        );
    }

    private BizAppInstallation installation() {
        BizAppInstallation inst = new BizAppInstallation();
        inst.setId(UUID.randomUUID());
        inst.setOrgId(orgId);
        inst.setTenantId(tenantId);
        inst.setKind("crm");
        inst.setConfig(Map.of("knowledgeBaseId", kbId.toString()));
        return inst;
    }

    @Test
    void projectsRecordAndOmitsPiiField() {
        BizAppRecord record = new BizAppRecord();
        record.setId(UUID.randomUUID());
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("crm");
        record.setEntity("leads");
        record.setStatus("ACTIVE");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "John Doe");
        payload.put("phone", "+84901234567"); // PII
        payload.put("stage", "WON");
        record.setPayload(payload);

        projector.projectRecord(tenantId, actorId, installation(), manifest(true), record);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentApi).ingestText(
                eq(tenantId), eq(actorId), eq(kbId),
                eq("bizapp__crm__leads__" + record.getId() + ".md"),
                markdownCaptor.capture(),
                eq(com.openvie.api.document.api.DocumentKind.BIZAPP_RECORD),
                eq("bizapp:crm")
        );

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("# John Doe"));
        assertTrue(markdown.contains("stage**: WON"));
        assertFalse(markdown.contains("+84901234567"), "PII phone must never be projected into knowledge base");
    }

    @Test
    void failsClosedWhenRbacReadIsRestricted() {
        BizAppRecord record = new BizAppRecord();
        record.setId(UUID.randomUUID());
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("crm");
        record.setEntity("leads");
        record.setStatus("ACTIVE");
        record.setPayload(Map.of("name", "Top Secret Client"));

        projector.projectRecord(tenantId, actorId, installation(), manifest(false), record);

        verify(documentApi, never()).ingestText(any(), any(), any(), any(), any());
    }

    @Test
    void archiveRecordTriggersDocumentDeletion() {
        BizAppRecord record = new BizAppRecord();
        record.setId(UUID.randomUUID());
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("crm");
        record.setEntity("leads");
        record.setStatus("ARCHIVED");

        projector.projectRecord(tenantId, actorId, installation(), manifest(true), record);

        verify(documentApi).deleteByFileName(tenantId, kbId, "bizapp__crm__leads__" + record.getId() + ".md");
    }
}
