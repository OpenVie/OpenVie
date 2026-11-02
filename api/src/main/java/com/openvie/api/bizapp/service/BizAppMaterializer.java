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

import com.openvie.api.bizapp.model.BizAppMarketplaceTemplate;
import com.openvie.api.bizapp.repository.BizAppMarketplaceTemplateRepository;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified template resolution for install + catalog + remix:
 * {@code platform:<kind>} loads a bundled JSON; a bare UUID loads the org's
 * own template row. The resolver unwraps the {@code openvie.template.v1} envelope
 * when present and owns all Jackson/IO failure translation.
 */
@Component
@RequiredArgsConstructor
public class BizAppMaterializer {

    private static final String BUNDLED_DIR = "marketplace/bizapps";

    private final ObjectMapper objectMapper;
    private final BizAppMarketplaceTemplateRepository templateRepository;

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadRaw(UUID orgId, String templateRef) {
        Map<String, Object> raw = loadRawEnvelope(orgId, templateRef);
        if (raw.containsKey("envelope") && "openvie.template.v1".equals(raw.get("envelope"))) {
            Object manifestObj = raw.get("manifest");
            if (manifestObj instanceof Map<?, ?> manifestMap) {
                return (Map<String, Object>) manifestMap;
            }
        }
        return raw;
    }

    public Map<String, Object> loadRawEnvelope(UUID orgId, String templateRef) {
        if (templateRef == null || templateRef.isBlank()) {
            throw new BadRequestException("templateRef is required");
        }
        if (templateRef.startsWith("platform:")) {
            return loadPlatform(templateRef.substring("platform:".length()).trim());
        }
        try {
            UUID customId = UUID.fromString(templateRef.trim());
            BizAppMarketplaceTemplate template = templateRepository.findByIdAndOrgId(customId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
            return template.getManifest();
        } catch (IllegalArgumentException notAnId) {
            throw new BadRequestException(
                    "templateRef must be `platform:<kind>` or an organization template id");
        }
    }

    private Map<String, Object> loadPlatform(String kind) {
        ClassPathResource resource = new ClassPathResource(BUNDLED_DIR + "/" + kind + ".json");
        if (!resource.exists()) {
            throw new ResourceNotFoundException("Platform template " + kind + " not found");
        }
        return read(resource);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> read(org.springframework.core.io.Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new ResourceNotFoundException("Template resource unreadable: " + exception.getMessage());
        }
    }

    Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new BadRequestException("Template is not a valid manifest JSON");
        }
    }

    /** Catalog path: unreadable bundled templates are skipped, not fatal. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> readOptional(org.springframework.core.io.Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            Map<String, Object> parsed = parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            if (parsed.containsKey("envelope") && "openvie.template.v1".equals(parsed.get("envelope"))) {
                Object manifestObj = parsed.get("manifest");
                if (manifestObj instanceof Map<?, ?> manifestMap) {
                    return Optional.of((Map<String, Object>) manifestMap);
                }
            }
            return Optional.of(parsed);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
