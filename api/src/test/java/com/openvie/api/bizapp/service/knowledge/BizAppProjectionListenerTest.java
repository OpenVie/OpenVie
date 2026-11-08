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

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.service.BizAppManifestParser;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.BizAppStorageStore;
import com.openvie.api.common.event.BizAppLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Projection writes a documents row whose uploaded_by is a users(id) foreign
 * key, so the actor must be a real user. Passing the tenant id (the previous
 * behaviour) made every ingest fail with a FK violation while the record was
 * marked FAILED — the reason a projected BizApp had no knowledge at all.
 */
class BizAppProjectionListenerTest {

    private static final String KIND = "aq-mqtt-demo";
    private static final String ENTITY = "devices";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID installerId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();

    private BizAppKnowledgeProjector projector;
    private BizAppProjectionListener listener;
    private Manifest manifest;
    private BizAppInstallation installation;

    @BeforeEach
    void setUp() {
        projector = mock(BizAppKnowledgeProjector.class);
        BizAppInstallationRepository installations = mock(BizAppInstallationRepository.class);
        BizAppManifestParser parser = mock(BizAppManifestParser.class);
        BizAppStorageRouter router = mock(BizAppStorageRouter.class);
        BizAppStorageStore store = mock(BizAppStorageStore.class);

        listener = new BizAppProjectionListener(projector, installations, parser, router);

        installation = new BizAppInstallation();
        installation.setId(UUID.randomUUID());
        installation.setTenantId(tenantId);
        installation.setOrgId(orgId);
        installation.setKind(KIND);
        installation.setVersion(1);
        installation.setStatus("ACTIVE");
        installation.setInstalledBy(installerId);
        when(installations.findByTenantIdAndKindAndStatus(tenantId, KIND, "ACTIVE"))
                .thenReturn(Optional.of(installation));

        Field deviceId = new Field("deviceId", "string", true, 64, null, null);
        manifest = new Manifest("bizapp.v1", KIND, new Localized("AQ", "AQ"), 1, "icon", "jsonb",
                Map.of(), List.of(new Entity(ENTITY, new Localized("Thiết bị", "Devices"),
                        List.of(deviceId), Map.of())), List.of(), null);
        when(parser.parse(any())).thenReturn(manifest);

        BizAppRecord record = new BizAppRecord();
        record.setId(recordId);
        record.setTenantId(tenantId);
        record.setAppKind(KIND);
        record.setEntity(ENTITY);
        when(router.route(KIND, ENTITY)).thenReturn(store);
        when(store.findById(tenantId, KIND, recordId)).thenReturn(Optional.of(record));
    }

    @Test
    void projectsWithTheInstallerWhenTheEventCarriesNoActor() {
        listener.onBizAppEvent(event("record.updated", Map.of("entity", ENTITY)));

        verify(projector).projectRecord(eq(tenantId), eq(installerId), eq(installation), eq(manifest), any());
    }

    @Test
    void prefersTheActingUserCarriedByTheEvent() {
        listener.onBizAppEvent(event("record.created", Map.of("created_by", authorId.toString())));

        verify(projector).projectRecord(eq(tenantId), eq(authorId), eq(installation), eq(manifest), any());
    }

    @Test
    void ignoresEventsWithoutASourceRecord() {
        listener.onBizAppEvent(new BizAppLifecycleEvent(this, orgId, tenantId,
                "bizapp." + KIND + ".record.updated", null, Map.of()));

        verify(projector, org.mockito.Mockito.never()).projectRecord(any(), any(), any(), any(), any());
    }

    private BizAppLifecycleEvent event(String suffix, Map<String, Object> data) {
        return new BizAppLifecycleEvent(this, orgId, tenantId,
                "bizapp." + KIND + "." + suffix, recordId, data);
    }
}
