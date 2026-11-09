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

package com.openvie.api.bizapp.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppConnectorJob;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppConnectorJobRepository;
import com.openvie.api.bizapp.service.BizAppRuntime;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.JsonbRecordStore;
import com.openvie.api.common.enums.LogAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppConnectorExecutorTest {

    private BizAppConnectorSink sink;
    private BizAppConnectorJobRepository jobRepository;
    private JsonbRecordStore store;
    private BizAppRuntime runtime;
    private SimpleMeterRegistry metrics;
    private ConnectorAdapter adapter;
    private BizAppConnectorExecutor executor;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private static final Manifest MANIFEST = new Manifest(
            "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
            Map.of("read", List.of("MEMBER")),
            List.of(new Entity("devices", new Localized("Devices", "Devices"), List.of(
                    new Field("name", "string", false, 100, null, null),
                    new Field("temperature", "number", false, null, null, null)),
                    null, Map.of())),
            List.of(), null);

    @BeforeEach
    void setUp() {
        sink = mock(BizAppConnectorSink.class);
        jobRepository = mock(BizAppConnectorJobRepository.class);
        store = mock(JsonbRecordStore.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var router = mock(BizAppStorageRouter.class);
        when(router.route(anyString(), anyString())).thenReturn(store);
        runtime = mock(BizAppRuntime.class);
        metrics = new SimpleMeterRegistry();
        adapter = mock(ConnectorAdapter.class);
        when(adapter.protocol()).thenReturn("http");
        var registry = new ConnectorAdapterRegistry(List.of(adapter));
        executor = new BizAppConnectorExecutor(new BizAppConnectorSpecParser(), registry, sink,
                jobRepository, router, runtime, metrics, new ObjectMapper());
    }

    private BizAppInstallation installation() {
        BizAppInstallation installation = new BizAppInstallation();
        installation.setId(installationId);
        installation.setOrgId(orgId);
        installation.setTenantId(tenantId);
        installation.setKind("ot-telemetry");
        installation.setConfig(Map.of("allowedHosts", List.of("ot-gateway.test")));
        installation.setInstalledBy(actorId);
        return installation;
    }

    private BizAppRecord record() {
        BizAppRecord record = new BizAppRecord();
        record.setId(recordId);
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("ot-telemetry");
        record.setEntity("devices");
        record.setPayload(new LinkedHashMap<>(Map.of("name", "Boiler 1")));
        Map<String, Object> runtimeState = new LinkedHashMap<>();
        runtimeState.put("external", Map.of("previousBlock", Map.of("id", "old")));
        record.setRuntime(runtimeState);
        record.setStatus("ACTIVE");
        record.setCreatedBy(actorId);
        return record;
    }

    private Block block() {
        return new Block("readTemp", "external_call", Map.of(
                "url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key",
                "responseMap", Map.of("temperature", "/temp", "provider", "/provider")));
    }

    private BizAppConnectorJob job(int attempt, int maxAttempts) {
        BizAppConnectorJob job = new BizAppConnectorJob();
        job.setId(jobId);
        job.setAttempt(attempt);
        job.setMaxAttempts(maxAttempts);
        job.setStatus("RUNNING");
        return job;
    }

    @Test
    void mergesRuntimeAndWritesTheSinkInOnePass() {
        when(adapter.execute(any(), any())).thenReturn(new ConnectorResponse(
                200, "{\"temp\":42.5,\"provider\":\"acme\"}", "ot-gateway.test"));
        when(sink.apply(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(0, 3)));
        BizAppRecord record = record();

        ConnectorOutcome outcome = executor.execute(installation(), MANIFEST, block(), record,
                actorId, jobId);

        assertTrue(outcome.summary().contains("sink"));
        verify(store).save(record);
        @SuppressWarnings("unchecked")
        Map<String, Object> external = (Map<String, Object>) record.getRuntime().get("external");
        assertTrue(external.containsKey("previousBlock"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) external.get("readTemp");
        assertEquals("42.5", extracted.get("temperature"));
        assertEquals("acme", extracted.get("provider"));

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(runtime).audit(eq(tenantId), eq(actorId), eq(LogAction.BIZAPP_EXTERNAL_CALL),
                eq("bizapp_record"), eq(recordId), metadata.capture());
        assertEquals("SUCCEEDED", metadata.getValue().get("outcome"));
        assertEquals(200, metadata.getValue().get("status"));
        assertEquals("ot-gateway.test", metadata.getValue().get("host"));
        assertEquals(1, metadata.getValue().get("attempt"));
        assertEquals(jobId.toString(), metadata.getValue().get("jobId"));
        assertEquals(1.0, metrics.get("bizapp_connector_runs_total")
                .tag("outcome", "SUCCEEDED").counter().count());
    }

    @Test
    void reportsNoMappedValuesWhenNothingWasWritten() {
        when(adapter.execute(any(), any())).thenReturn(
                new ConnectorResponse(200, "{}", "ot-gateway.test"));
        when(sink.apply(any(), any(), any(), any(), any(), any())).thenReturn(false);
        var block = new Block("readTemp", "external_call", Map.of(
                "url", "https://ot-gateway.test/temp", "credentialRef", "gateway-key"));

        ConnectorOutcome outcome = executor.execute(installation(), MANIFEST, block, record(),
                actorId, null);

        assertEquals("no mapped values", outcome.summary());
    }

    @Test
    void rejectsInactiveRecordsBeforeCallingTheProvider() {
        BizAppRecord archived = record();
        archived.setStatus("ARCHIVED");

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> executor.execute(installation(), MANIFEST, block(), archived, actorId, jobId));
        assertFalse(error.retryable());
        assertEquals("record is not active", error.getMessage());
    }

    @Test
    void failsPermanentlyWhenNoAdapterIsRegistered() {
        var emptyExecutor = new BizAppConnectorExecutor(new BizAppConnectorSpecParser(),
                new ConnectorAdapterRegistry(List.of()), sink, jobRepository,
                mock(BizAppStorageRouter.class), runtime, metrics, new ObjectMapper());

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> emptyExecutor.execute(installation(), MANIFEST, block(), record(), actorId, jobId));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("no connector adapter"));
    }

    @Test
    void auditsRetryableAndTerminalFailuresAfterRollback() {
        BizAppRecord record = record();
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(0, 3)));

        executor.auditFailure(block(), record, actorId, jobId, System.nanoTime(),
                ConnectorException.retryable("HTTP 503"));
        assertEquals(1.0, metrics.get("bizapp_connector_runs_total")
                .tag("outcome", "RETRY").counter().count());

        executor.auditFailure(block(), record, actorId, jobId, System.nanoTime(),
                ConnectorException.permanent("HTTP 404"));
        assertEquals(1.0, metrics.get("bizapp_connector_runs_total")
                .tag("outcome", "DEAD").counter().count());

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(runtime, org.mockito.Mockito.times(2)).audit(eq(tenantId), eq(actorId),
                eq(LogAction.BIZAPP_EXTERNAL_CALL), eq("bizapp_record"), eq(recordId),
                metadata.capture());
        assertEquals("RETRY", metadata.getAllValues().get(0).get("outcome"));
        assertEquals("DEAD", metadata.getAllValues().get(1).get("outcome"));
        assertEquals("HTTP 404", metadata.getAllValues().get(1).get("error"));
    }

    @Test
    void reportsDeadWhenARetryableFailureExhaustsTheBudget() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job(2, 3)));

        executor.auditFailure(block(), record(), actorId, jobId,
                System.nanoTime(), ConnectorException.retryable("timeout"));
        assertEquals(1.0, metrics.get("bizapp_connector_runs_total")
                .tag("outcome", "DEAD").counter().count());
    }
}
