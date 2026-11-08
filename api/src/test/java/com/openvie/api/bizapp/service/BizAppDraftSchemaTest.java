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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.GenerateDraftRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.ValidationResult;
import com.openvie.api.bizapp.model.BizAppDraft;
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drafting must hand the model the actual JSON Schema: the AI service only
 * applies structured output when the request carries the schema body, and a
 * bare file name ("bizapp.v1.schema.json") is treated as a legacy reference
 * and ignored. Without this the model is free to emit keys the contract drops.
 */
class BizAppDraftSchemaTest {

    private static final String VALID_JSON = """
            {"schema":"bizapp.v1","kind":"aq-demo","version":1,"storage":"jsonb",
             "name":{"en":"AQ Demo","vi":"Demo"},
             "rbac":{"read":["ORG_OWNER","WORKSPACE_ADMIN","MEMBER"],"write":["WORKSPACE_ADMIN"]},
             "entities":[{"name":"devices","label":{"en":"Devices","vi":"Thiết bị"},
               "fields":[{"name":"deviceId","type":"string","required":true,"maxLen":64}]}]}
            """;

    private AiInferenceApi aiInferenceApi;
    private BizAppDraftService service;

    @BeforeEach
    void setUp() {
        aiInferenceApi = mock(AiInferenceApi.class);
        BizAppDraftRepository draftRepository = mock(BizAppDraftRepository.class);
        BizAppProperties properties = mock(BizAppProperties.class);
        when(properties.aiDraftingEnabled()).thenReturn(true);
        when(draftRepository.save(any(BizAppDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new BizAppDraftService(
                draftRepository,
                mock(BizAppInstallationRepository.class),
                new BizAppManifestValidator(),
                new BizAppManifestParser(new ObjectMapper()),
                aiInferenceApi,
                properties,
                mock(BizAppRuntime.class),
                new ObjectMapper());
    }

    @Test
    void shipsTheSchemaBodyNotAFileName() {
        String schema = service.manifestSchema();

        assertTrue(schema.startsWith("{"), "the AI service ignores anything that is not JSON");
        assertTrue(schema.contains("\"additionalProperties\": false"),
                "unknown manifest keys must be impossible, not merely ignored");
        assertTrue(schema.contains("\"entities\""));
    }

    @Test
    void sendsTheSchemaBodyToTheModel() {
        when(aiInferenceApi.completeJson(any())).thenReturn(
                new AiInferenceApi.StructuredJsonResponse(VALID_JSON, "test-model", 1, 1));

        service.generateDraft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new GenerateDraftRequest("app thu thập chất lượng không khí", "vi", null));

        ArgumentCaptor<AiInferenceApi.StructuredJsonRequest> captor =
                ArgumentCaptor.forClass(AiInferenceApi.StructuredJsonRequest.class);
        verify(aiInferenceApi).completeJson(captor.capture());
        assertTrue(captor.getValue().jsonSchema().startsWith("{"), captor.getValue().jsonSchema());
        assertTrue(captor.getValue().jsonSchema().contains("\"additionalProperties\": false"));
    }

    @Test
    void rejectsTheKeysTheModelUsuallyInvents() {
        Map<String, Object> manifest = new ObjectMapper().convertValue(
                Map.of("schema", "bizapp.v1", "kind", "aq-demo", "version", 1, "storage", "jsonb",
                        "name", Map.of("vi", "Demo", "en", "Demo"),
                        "rbac", Map.of("read", List.of("MEMBER")),
                        "entities", List.of(Map.of("name", "devices",
                                "fields", List.of(Map.of("name", "deviceId", "type", "string",
                                        "maxLength", 64)))),
                        "reports", List.of(Map.of("view", "by_device")),
                        "hooks", Map.of("devices", List.of(Map.of("onCreate", List.of("poll"))))),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

        ValidationResult result = service.validateStateless(manifest);

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("manifest.reports")),
                result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("manifest.hooks")),
                result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("maxLen")),
                result.errors().toString());
    }
}
