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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract guard exists because Jackson drops unknown properties: a draft
 * with top-level {@code reports}/{@code hooks} or a field {@code maxLength}
 * used to validate as {@code ok:true} while silently losing the report, the
 * hook and the length limit. These tests pin the corrections the messages
 * must carry so the drafting repair loop can act on them.
 */
class BizAppManifestKeysTest {

    @Test
    void acceptsAValidManifest() {
        assertEquals(List.of(), BizAppManifestKeys.unknownKeys(validManifest()));
    }

    @Test
    void pointsATopLevelReportsKeyAtTheReportBlock() {
        Map<String, Object> manifest = validManifest();
        manifest.put("reports", List.of(Map.of("view", "by_device")));

        List<String> messages = BizAppManifestKeys.unknownKeys(manifest);

        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("manifest.reports"), messages.get(0));
        assertTrue(messages.get(0).contains("\"report\" block"), messages.get(0));
    }

    @Test
    void pointsATopLevelHooksKeyAtTheEntityHooks() {
        Map<String, Object> manifest = validManifest();
        manifest.put("hooks", Map.of("devices", List.of(Map.of("onCreate", List.of("poll")))));

        List<String> messages = BizAppManifestKeys.unknownKeys(manifest);

        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("entities[].hooks"), messages.get(0));
    }

    @Test
    void flagsTheMaxLengthTypoWithTheContractKey() {
        Map<String, Object> manifest = validManifest();
        field(manifest).put("maxLength", 64);

        List<String> messages = BizAppManifestKeys.unknownKeys(manifest);

        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("maxLen"), messages.get(0));
    }

    @Test
    void walksReportViewsMetricsAndOrdering() {
        Map<String, Object> manifest = validManifest();
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("fn", "avg");
        metric.put("field", "pm25");
        metric.put("alias", "avg_pm25");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", "by_device");
        view.put("entity", "readings");
        view.put("metrics", List.of(metric));
        view.put("sort", List.of(Map.of("by", "ts")));
        blocks(manifest).add(Map.of("id", "reports", "type", "report",
                "params", Map.of("views", List.of(view))));

        List<String> messages = BizAppManifestKeys.unknownKeys(manifest);

        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("metrics[].alias")), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.contains("views[by_device].sort")), messages.toString());
    }

    @Test
    void capsTheMessagesSoAMalformedDraftCannotFloodThePanel() {
        Map<String, Object> manifest = validManifest();
        for (int index = 0; index < 30; index++) {
            manifest.put("bogus" + index, index);
        }

        List<String> messages = BizAppManifestKeys.unknownKeys(manifest);

        assertEquals(12, messages.size(), messages.toString());
    }

    // ------------------------------------------------------------ fixtures

    private Map<String, Object> validManifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "bizapp.v1");
        manifest.put("kind", "aq-mqtt-demo");
        manifest.put("version", 1);
        manifest.put("storage", "jsonb");
        manifest.put("name", Map.of("vi", "Demo", "en", "Demo"));
        manifest.put("rbac", Map.of("read", List.of("MEMBER")));
        manifest.put("entities", new ArrayList<>(List.of(deviceEntity())));
        manifest.put("blocks", new ArrayList<>(List.of(Map.of(
                "id", "poll", "type", "external_call",
                "params", Map.of("protocol", "mqtt", "brokerUrl", "tcp://localhost:1883",
                        "topic", "openvie/aq/aq-01/telemetry")))));
        manifest.put("ui", Map.of("views", List.of("registry:devices"),
                "layout", Map.of("devices", Map.of("x", 50, "y", 50))));
        manifest.put("conversational", Map.of("triggers", List.of("không khí")));
        return manifest;
    }

    private Map<String, Object> deviceEntity() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", "deviceId");
        field.put("type", "string");
        field.put("maxLen", 64);
        field.put("label", Map.of("vi", "Mã", "en", "ID"));
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", "devices");
        entity.put("label", Map.of("vi", "Thiết bị", "en", "Devices"));
        entity.put("fields", new ArrayList<>(List.of(field)));
        entity.put("knowledge", Map.of("project", true, "titleField", "deviceId",
                "fields", List.of("deviceId")));
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(Map<String, Object> manifest) {
        Map<String, Object> entity = ((List<Map<String, Object>>) manifest.get("entities")).get(0);
        return ((List<Map<String, Object>>) entity.get("fields")).get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> blocks(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("blocks");
    }
}
