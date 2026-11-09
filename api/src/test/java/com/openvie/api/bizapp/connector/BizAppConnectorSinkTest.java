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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Localized;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.bizapp.service.BizAppPayloadValidator;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import com.openvie.api.bizapp.service.storage.JsonbRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppConnectorSinkTest {

    private BizAppRecordRepository recordRepository;
    private JsonbRecordStore store;
    private BizAppConnectorSink sink;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private static final Manifest MANIFEST = new Manifest(
            "bizapp.v1", "ot-telemetry", new Localized("OT", "OT"), 1, "cpu",
            Map.of("read", List.of("MEMBER")),
            List.of(
                    new Entity("devices", new Localized("Devices", "Devices"), List.of(
                            new Field("name", "string", true, 100, null, null),
                            new Field("enabled", "boolean", false, null, null, null),
                            new Field("temperature", "number", false, null, null, null),
                            new Field("lastSeen", "date", false, null, null, null)),
                            null, Map.of()),
                    new Entity("telemetry", new Localized("Telemetry", "Telemetry"), List.of(
                            new Field("deviceId", "string", true, 100, null, null),
                            new Field("ts", "date", true, null, null, null),
                            new Field("temperature", "number", false, null, null, null)),
                            null, Map.of())),
            List.of(), null);

    @BeforeEach
    void setUp() {
        recordRepository = mock(BizAppRecordRepository.class);
        store = mock(JsonbRecordStore.class);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var router = mock(BizAppStorageRouter.class);
        when(router.route(anyString(), anyString())).thenReturn(store);
        sink = new BizAppConnectorSink(new BizAppPayloadValidator(), recordRepository,
                router, new ObjectMapper());
    }

    private BizAppRecord deviceRecord() {
        BizAppRecord record = new BizAppRecord();
        record.setId(UUID.randomUUID());
        record.setOrgId(orgId);
        record.setTenantId(tenantId);
        record.setAppKind("ot-telemetry");
        record.setEntity("devices");
        record.setPayload(new LinkedHashMap<>(Map.of("name", "Boiler 1")));
        record.setRuntime(Map.of());
        record.setStatus("ACTIVE");
        record.setCreatedBy(actorId);
        return record;
    }

    private static BizAppConnectorSpec.Sink fieldSink(Map<String, String> mapping) {
        return new BizAppConnectorSpec.Sink("field", null, mapping, List.of());
    }

    private static BizAppConnectorSpec.Sink recordSink() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("deviceId", "/device");
        mapping.put("ts", "/ts");
        mapping.put("temperature", "/temp");
        return new BizAppConnectorSpec.Sink("record", "telemetry", mapping,
                List.of("deviceId", "ts"));
    }

    @Test
    void fieldSinkCoercesByDeclaredTypeAndMergesIntoTheRecord() {
        BizAppRecord record = deviceRecord();
        ConnectorResponse response = new ConnectorResponse(200,
                "{\"active\":\"1\",\"temp\":\"42.5\",\"seen\":\"2026-01-01T10:30\"}", "ot-gateway.test");

        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("enabled", "/active");
        mapping.put("temperature", "/temp");
        mapping.put("lastSeen", "/seen");

        boolean applied = sink.apply(new BizAppInstallation(), MANIFEST, fieldSink(mapping),
                response, record, actorId);

        assertTrue(applied);
        assertEquals("Boiler 1", record.getPayload().get("name"));
        assertEquals(Boolean.TRUE, record.getPayload().get("enabled"));
        assertEquals(0, new BigDecimal("42.5").compareTo(
                (BigDecimal) record.getPayload().get("temperature")));
        assertEquals("2026-01-01T10:30", record.getPayload().get("lastSeen"));
    }

    @Test
    void rejectsMappedFieldsTheEntityDoesNotDeclare() {
        BizAppRecord record = deviceRecord();
        ConnectorException error = assertThrows(ConnectorException.class,
                () -> sink.apply(new BizAppInstallation(), MANIFEST,
                        fieldSink(Map.of("ghost", "/ghost")),
                        new ConnectorResponse(200, "{\"ghost\":1}", "ot-gateway.test"),
                        record, actorId));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("is not declared on devices"));
        assertTrue(record.getPayload().containsKey("name"));
        assertFalse(record.getPayload().containsKey("ghost"));
    }

    @Test
    void recordSinkCreatesADedupedTelemetryRow() {
        BizAppRecord source = deviceRecord();
        when(recordRepository.findByTenantIdAndAppKindAndEntityAndDedupeKey(
                any(), any(), any(), any())).thenReturn(Optional.empty());

        boolean applied = sink.apply(new BizAppInstallation(), MANIFEST, recordSink(),
                new ConnectorResponse(200,
                        "{\"device\":\"d-1\",\"ts\":\"2026-01-01T00:00\",\"temp\":42.5}",
                        "ot-gateway.test"),
                source, actorId);

        assertTrue(applied);
        ArgumentCaptor<BizAppRecord> saved = ArgumentCaptor.forClass(BizAppRecord.class);
        verify(store).save(saved.capture());
        BizAppRecord telemetry = saved.getValue();
        assertEquals("telemetry", telemetry.getEntity());
        assertEquals("d-1", telemetry.getPayload().get("deviceId"));
        assertEquals("2026-01-01T00:00", telemetry.getPayload().get("ts"));
        assertEquals(0, new BigDecimal("42.5").compareTo(
                (BigDecimal) telemetry.getPayload().get("temperature")));
        assertNotNull(telemetry.getDedupeKey());
        assertEquals(64, telemetry.getDedupeKey().length());
        assertEquals("ACTIVE", telemetry.getStatus());
        assertEquals(actorId, telemetry.getCreatedBy());
        verify(recordRepository).lockDedupeKey(anyString());
    }

    @Test
    void recordSinkMergesIntoTheExistingDedupeRow() {
        BizAppRecord source = deviceRecord();
        BizAppRecord existing = new BizAppRecord();
        existing.setId(UUID.randomUUID());
        existing.setPayload(new LinkedHashMap<>(Map.of(
                "deviceId", "d-1", "ts", "2026-01-01T00:00", "temperature", new BigDecimal("10"))));
        when(recordRepository.findByTenantIdAndAppKindAndEntityAndDedupeKey(
                any(), any(), any(), any())).thenReturn(Optional.of(existing));

        sink.apply(new BizAppInstallation(), MANIFEST, recordSink(),
                new ConnectorResponse(200,
                        "{\"device\":\"d-1\",\"ts\":\"2026-01-01T00:00\",\"temp\":42.5}",
                        "ot-gateway.test"),
                source, actorId);

        ArgumentCaptor<BizAppRecord> saved = ArgumentCaptor.forClass(BizAppRecord.class);
        verify(store).save(saved.capture());
        BizAppRecord telemetry = saved.getValue();
        assertEquals(existing.getId(), telemetry.getId());
        assertEquals(0, new BigDecimal("42.5").compareTo(
                (BigDecimal) telemetry.getPayload().get("temperature")));
        assertEquals(actorId, telemetry.getUpdatedBy());
    }

    @Test
    void recordSinkRequiresEveryDedupeField() {
        BizAppRecord source = deviceRecord();
        ConnectorException error = assertThrows(ConnectorException.class,
                () -> sink.apply(new BizAppInstallation(), MANIFEST, recordSink(),
                        new ConnectorResponse(200, "{\"device\":\"d-1\",\"temp\":1}",
                                "ot-gateway.test"),
                        source, actorId));
        assertFalse(error.retryable());
        assertEquals("dedupe field ts missing from connector response", error.getMessage());
        verify(store, never()).save(any());
    }

    @Test
    void dedupeHashIsStableAcrossFieldOrder() {
        String first = BizAppConnectorSink.dedupeHash(List.of("deviceId", "ts"),
                Map.of("deviceId", "d-1", "ts", "2026-01-01"));
        String reversed = BizAppConnectorSink.dedupeHash(List.of("ts", "deviceId"),
                Map.of("deviceId", "d-1", "ts", "2026-01-01"));
        assertEquals(first, reversed);
        assertEquals(64, first.length());
    }

    @Test
    void emptyResponsesAreNotWritten() {
        BizAppRecord source = deviceRecord();
        boolean applied = sink.apply(new BizAppInstallation(), MANIFEST, recordSink(),
                new ConnectorResponse(200, "{}", "ot-gateway.test"), source, actorId);
        assertFalse(applied);
        verify(store, never()).save(any());
    }
}
