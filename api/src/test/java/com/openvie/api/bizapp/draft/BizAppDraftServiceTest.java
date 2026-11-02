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

package com.openvie.api.bizapp.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.GenerateDraftRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.SaveDraftRequest;
import com.openvie.api.bizapp.model.BizAppDraft;
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
import com.openvie.api.bizapp.service.BizAppDraftService;
import com.openvie.api.bizapp.service.BizAppManifestParser;
import com.openvie.api.bizapp.service.BizAppManifestValidator;
import com.openvie.api.bizapp.service.BizAppRuntime;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class BizAppDraftServiceTest {

    private BizAppDraftRepository draftRepository;
    private BizAppManifestValidator validator;
    private BizAppManifestParser parser;
    private AiInferenceApi aiInferenceApi;
    private BizAppRuntime runtime;
    private ObjectMapper objectMapper;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        draftRepository = mock(BizAppDraftRepository.class);
        validator = new BizAppManifestValidator();
        objectMapper = new ObjectMapper();
        parser = new BizAppManifestParser(objectMapper);
        aiInferenceApi = mock(AiInferenceApi.class);
        runtime = mock(BizAppRuntime.class);
    }

    @Test
    void statelessValidationReportsErrorsOnInvalidManifest() {
        var service = new BizAppDraftService(
                draftRepository, validator, parser, aiInferenceApi,
                new BizAppProperties(true, false, false, false, ""),
                runtime, objectMapper
        );

        var result = service.validateStateless(Map.of("schema", "invalid.v1"));
        assertFalse(result.ok());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void statelessValidationSucceedsOnValidManifest() {
        var service = new BizAppDraftService(
                draftRepository, validator, parser, aiInferenceApi,
                new BizAppProperties(true, false, false, false, ""),
                runtime, objectMapper
        );

        Map<String, Object> manifest = Map.of(
                "schema", "bizapp.v1",
                "kind", "inventory",
                "name", Map.of("en", "Inventory", "vi", "Kho"),
                "version", 1,
                "entities", List.of(
                        Map.of(
                                "name", "items",
                                "label", Map.of("en", "Items", "vi", "San pham"),
                                "fields", List.of(
                                        Map.of("name", "sku", "type", "string", "required", true)
                                )
                        )
                )
        );

        var result = service.validateStateless(manifest);
        assertTrue(result.ok());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void generateDraftThrowsWhenAiDraftingDisabled() {
        var service = new BizAppDraftService(
                draftRepository, validator, parser, aiInferenceApi,
                new BizAppProperties(true, false, false, false, ""), // disabled
                runtime, objectMapper
        );

        assertThrows(ResourceNotFoundException.class,
                () -> service.generateDraft(orgId, tenantId, actorId, new GenerateDraftRequest("make an app", "en", null)));
    }

    @Test
    void generateDraftInvokesAiAndStoresDraft() {
        var service = new BizAppDraftService(
                draftRepository, validator, parser, aiInferenceApi,
                new BizAppProperties(true, false, true, false, ""), // enabled
                runtime, objectMapper
        );

        String validAiJson = """
                {
                  "schema": "bizapp.v1",
                  "kind": "tasks",
                  "name": { "en": "Tasks", "vi": "Cong viec" },
                  "version": 1,
                  "entities": [
                    {
                      "name": "tasks",
                      "label": { "en": "Tasks", "vi": "Cong viec" },
                      "fields": [
                        { "name": "title", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """;

        when(aiInferenceApi.completeJson(any())).thenReturn(
                new AiInferenceApi.StructuredJsonResponse(validAiJson, "vylinh", 50, 100)
        );
        when(draftRepository.save(any())).thenAnswer(inv -> {
            BizAppDraft d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        var resp = service.generateDraft(orgId, tenantId, actorId, new GenerateDraftRequest("task tracking app", "en", null));

        assertNotNull(resp.draftId());
        assertTrue(resp.validation().ok());
        assertEquals("tasks", resp.manifest().get("kind"));
        assertEquals("ai", resp.meta().get("generator"));
        verify(draftRepository).save(any(BizAppDraft.class));
    }

    @Test
    void saveAndGetDraftLifecycle() {
        var service = new BizAppDraftService(
                draftRepository, validator, parser, aiInferenceApi,
                new BizAppProperties(true, false, false, false, ""),
                runtime, objectMapper
        );

        UUID draftId = UUID.randomUUID();
        when(draftRepository.save(any())).thenAnswer(inv -> {
            BizAppDraft d = inv.getArgument(0);
            if (d.getId() == null) d.setId(draftId);
            return d;
        });

        var saved = service.saveDraft(orgId, tenantId, actorId, new SaveDraftRequest(
                null, "Canvas Draft", "canvas", Map.of("schema", "bizapp.v1", "kind", "crm"), null
        ));

        assertNotNull(saved.id());
        assertEquals("Canvas Draft", saved.title());
        assertEquals("canvas", saved.source());
    }
}
