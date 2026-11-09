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

package com.openvie.api.bizapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openvie.api.bizapp.BizAppConnectorProperties;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.connector.BizAppConnectorSpecParser;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.JsonbRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppConnectorSchedulerTest {

    private BizAppConnectorJobService jobService;
    private BizAppConnectorJobRepository jobRepository;
    private BizAppInstallationRepository installationRepository;
    private BizAppManifestParser manifestParser;
    private JsonbRecordStore store;
    private BizAppConnectorScheduler scheduler;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private static final Manifest MANIFEST = new Manifest(
            "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
            Map.of("read", List.of("MEMBER")),
            List.of(new Entity("devices", new Localized("Devices", "Devices"), List.of(
                    new Field("name", "string", false, 100, null, null),
                    new Field("enabled", "boolean", false, null, null, null)), null, Map.of())),
            List.of(new Block("readTemp", "external_call", Map.of(
                    "url", "https://ot-gateway.test/temp",
                    "credentialRef", "gateway-key",
                    "schedule", Map.of(
                            "every", "PT1M",
                            "entity", "devices",
                            "select", Map.of("enabled", true),
                            "limit", 2)))),
            null);

    @BeforeEach
    void setUp() {
        jobService = mock(BizAppConnectorJobService.class);
        jobRepository = mock(BizAppConnectorJobRepository.class);
        installationRepository = mock(BizAppInstallationRepository.class);
        manifestParser = mock(BizAppManifestParser.class);
        store = mock(JsonbRecordStore.class);
        var router = mock(BizAppStorageRouter.class);
        when(router.route(anyString(), anyString())).thenReturn(store);
        when(manifestParser.parse(any())).thenReturn(MANIFEST);
        when(installationRepository.findByStatus("ACTIVE")).thenReturn(List.of(installation()));
        scheduler = new BizAppConnectorScheduler(
                new BizAppProperties(true, false, false, false, ""),
                new BizAppConnectorProperties(true, 15_000, 5_000, 20),
                jobService, new BizAppConnectorSpecParser(), jobRepository,
                installationRepository, manifestParser, router);
    }

    private BizAppInstallation installation() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(installationId);
        installation.setOrgId(orgId);
        installation.setTenantId(tenantId);
        installation.setKind("ot-telemetry");
        installation.setManifest(Map.of());
        installation.setStatus("ACTIVE");
        installation.setInstalledBy(actorId);
        return installation;
    }

    private BizAppRecord device(String name, boolean enabled) {
        BizAppRecord record = new BizAppRecord();
        record.setId(UUID.randomUUID());
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("ot-telemetry");
        record.setEntity("devices");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("enabled", enabled);
        record.setPayload(payload);
        record.setStatus("ACTIVE");
        record.setCreatedBy(actorId);
        return record;
    }

    private void stubPage(List<BizAppRecord> records) {
        when(store.list(eq(tenantId), eq("ot-telemetry"), eq("devices"), eq(false), any()))
                .thenReturn(new PageImpl<>(records, PageRequest.of(0, 200), records.size()));
    }

    @Test
    void enqueuesMatchingRecordsUpToTheDeclaredLimit() {
        stubPage(List.of(device("a", true), device("b", true), device("c", true)));

        scheduler.scanSchedules();

        ArgumentCaptor<UUID> recordIds = ArgumentCaptor.forClass(UUID.class);
        verify(jobService, times(2)).enqueue(eq(tenantId), eq(orgId), eq("ot-telemetry"),
                eq("devices"), recordIds.capture(), eq("readTemp"), eq(actorId), eq("SCHEDULE"));
        assertEquals(2, recordIds.getAllValues().size());
    }

    @Test
    void filtersBySelectValues() {
        stubPage(List.of(device("a", true), device("b", false)));

        scheduler.scanSchedules();

        verify(jobService, times(1)).enqueue(any(), any(), any(), any(), any(), eq("readTemp"),
                any(), eq("SCHEDULE"));
    }

    @Test
    void skipsRecordsWithPendingOrRecentSuccessfulJobs() {
        BizAppRecord pending = device("a", true);
        BizAppRecord recent = device("b", true);
        BizAppRecord dead = device("c", true);
        when(jobRepository.findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
                installationId, "readTemp", pending.getId()))
                .thenReturn(Optional.of(job("PENDING", LocalDateTime.now().minusHours(1))));
        when(jobRepository.findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
                installationId, "readTemp", recent.getId()))
                .thenReturn(Optional.of(job("SUCCEEDED", LocalDateTime.now().minusSeconds(30))));
        when(jobRepository.findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
                installationId, "readTemp", dead.getId()))
                .thenReturn(Optional.of(job("DEAD", LocalDateTime.now().minusSeconds(30))));
        stubPage(List.of(pending, recent, dead));

        scheduler.scanSchedules();

        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void deadOrStaleJobsDoNotBlockTheNextPoll() {
        BizAppRecord dead = device("a", true);
        BizAppRecord stale = device("b", true);
        when(jobRepository.findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
                installationId, "readTemp", dead.getId()))
                .thenReturn(Optional.of(job("DEAD", LocalDateTime.now().minusMinutes(5))));
        when(jobRepository.findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
                installationId, "readTemp", stale.getId()))
                .thenReturn(Optional.of(job("SUCCEEDED", LocalDateTime.now().minusMinutes(5))));
        stubPage(List.of(dead, stale));

        scheduler.scanSchedules();

        verify(jobService, times(2)).enqueue(eq(tenantId), eq(orgId), eq("ot-telemetry"),
                eq("devices"), any(), eq("readTemp"), eq(actorId), eq("SCHEDULE"));
    }

    @Test
    void ignoresBlocksWithoutASchedule() {
        when(manifestParser.parse(any())).thenReturn(new Manifest(
                "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
                Map.of("read", List.of("MEMBER")),
                List.of(new Entity("devices", new Localized("Devices", "Devices"), List.of(
                        new Field("name", "string", false, 100, null, null)), null, Map.of())),
                List.of(new Block("readTemp", "external_call", Map.of(
                        "url", "https://ot-gateway.test/temp",
                        "credentialRef", "gateway-key"))),
                null));

        scheduler.scanSchedules();

        verifyNoInteractions(store);
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledConnectorsDoNotScan() {
        scheduler = new BizAppConnectorScheduler(
                new BizAppProperties(true, false, false, false, ""),
                new BizAppConnectorProperties(false, 15_000, 5_000, 20),
                jobService, new BizAppConnectorSpecParser(), jobRepository,
                installationRepository, manifestParser, mock(BizAppStorageRouter.class));

        scheduler.scanSchedules();

        verify(installationRepository, never()).findByStatus(anyString());
    }

    private BizAppConnectorJob job(String status, LocalDateTime createdAt) {
        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setId(UUID.randomUUID());
        job.setStatus(status);
        job.setCreatedAt(createdAt);
        return job;
    }
}
