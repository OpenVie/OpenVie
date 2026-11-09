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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the platform-bundled marketplace templates: every JSON under
 * {@code marketplace/bizapps} must parse and pass the manifest validator, so
 * {@code platform:<kind>} installs never 400 in production. Mirrors the
 * marketplace-validate CI job in-test.
 */
class BizAppBundledTemplatesTest {

    private final BizAppManifestValidator validator = new BizAppManifestValidator();

    @Test
    void everyBundledTemplateIsValidatesAsBizAppV1() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:marketplace/bizapps/*.json");
        assertTrue(resources.length >= 2, "bundled templates must ship (found "
                + resources.length + ")");

        ObjectMapper mapper = new ObjectMapper();
        for (Resource resource : resources) {
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if ("openvie.template.v1".equals(raw.get("envelope")) && raw.get("manifest") instanceof Map<?, ?> m) {
                raw = (Map<String, Object>) m;
            }
            validator.validate(mapper.convertValue(raw,
                    com.openvie.api.bizapp.dto.BizAppManifest.Manifest.class), true);
        }
    }
}
