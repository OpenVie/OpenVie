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

package com.openvie.api.bizapp.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.ImportTemplateRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.RemixRequest;
import com.openvie.api.bizapp.model.BizAppDraft;
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
import com.openvie.api.bizapp.repository.BizAppMarketplaceTemplateRepository;
import com.openvie.api.bizapp.service.BizAppManifestParser;
import com.openvie.api.bizapp.service.BizAppManifestValidator;
import com.openvie.api.bizapp.service.BizAppMarketplaceService;
import com.openvie.api.bizapp.service.BizAppMaterializer;
import com.openvie.api.bizapp.service.BizAppRuntime;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.net.SafeOutboundHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class BizAppTemplateExchangeTest {

    private BizAppMarketplaceTemplateRepository templateRepository;
    private BizAppDraftRepository draftRepository;
    private BizAppMaterializer materializer;
    private BizAppManifestParser parser;
    private BizAppManifestValidator validator;
    private BizAppRuntime runtime;
    private SafeOutboundHttp safeOutboundHttp;
    private ObjectMapper objectMapper;

    private BizAppMarketplaceService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        templateRepository = mock(BizAppMarketplaceTemplateRepository.class);
        draftRepository = mock(BizAppDraftRepository.class);
        materializer = mock(BizAppMaterializer.class);
        objectMapper = new ObjectMapper();
        parser = new BizAppManifestParser(objectMapper);
        validator = new BizAppManifestValidator();
        runtime = mock(BizAppRuntime.class);
        safeOutboundHttp = mock(SafeOutboundHttp.class);

        var props = new BizAppProperties(true, false, false, false, "");
        service = new BizAppMarketplaceService(
                templateRepository, draftRepository, materializer, parser,
                validator, runtime, props, safeOutboundHttp, objectMapper
        );
    }

    private Map<String, Object> sampleManifest() {
        return Map.of(
                "schema", "bizapp.v1",
                "kind", "support-tickets",
                "name", Map.of("en", "Support", "vi", "Ho tro"),
                "version", 1,
                "entities", List.of(
                        Map.of(
                                "name", "tickets",
                                "label", Map.of("en", "Tickets", "vi", "Phieu"),
                                "fields", List.of(
                                        Map.of("name", "title", "type", "string", "required", true)
                                )
                        )
                )
        );
    }

    @Test
    void rejectsImportWithNonAllowedSpdxLicense() {
        Map<String, Object> envelope = Map.of(
                "envelope", "openvie.template.v1",
                "meta", Map.of(
                        "author", "Acme",
                        "license", "GPL-3.0", // Forbidden license
                        "source", "import"
                ),
                "manifest", sampleManifest()
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.importTemplate(orgId, tenantId, actorId, new ImportTemplateRequest(json, null, null)));
        assertTrue(ex.getMessage().contains("not an allowed SPDX license"), ex.getMessage());
    }

    @Test
    void rejectsImportWithChecksumMismatch() {
        Map<String, Object> envelope = Map.of(
                "envelope", "openvie.template.v1",
                "meta", Map.of(
                        "author", "Acme",
                        "license", "Apache-2.0",
                        "source", "import",
                        "sha256", "0000000000000000000000000000000000000000000000000000000000000000" // Corrupted
                ),
                "manifest", sampleManifest()
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.importTemplate(orgId, tenantId, actorId, new ImportTemplateRequest(json, null, null)));
        assertTrue(ex.getMessage().contains("checksum mismatch"), ex.getMessage());
    }

    @Test
    void remixRetainsUpstreamMetadataAndAppendsChanges() {
        Map<String, Object> upstreamEnvelope = Map.of(
                "envelope", "openvie.template.v1",
                "meta", Map.of(
                        "author", "Original Author",
                        "license", "MIT",
                        "source", "bundled",
                        "changes", List.of()
                ),
                "manifest", sampleManifest()
        );

        when(materializer.loadRawEnvelope(orgId, "platform:support")).thenReturn(upstreamEnvelope);
        when(draftRepository.save(any())).thenAnswer(inv -> {
            BizAppDraft d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        var draft = service.remixTemplate(orgId, tenantId, actorId, "platform:support", new RemixRequest("Customized fields"));

        assertNotNull(draft.id());
        assertEquals("remix", draft.source());

        Map<String, Object> meta = draft.meta();
        assertEquals("Original Author", meta.get("author"));
        assertEquals("MIT", meta.get("license"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) meta.get("changes");
        assertEquals(1, changes.size());
        assertEquals("Customized fields", changes.get(0).get("summary"));
        assertEquals(orgId.toString(), changes.get(0).get("by"));
    }
}
