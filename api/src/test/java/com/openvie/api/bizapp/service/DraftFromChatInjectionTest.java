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
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DraftFromChatInjectionTest {

    private BizAppManifestParser parser;
    private BizAppManifestValidator validator;

    @BeforeEach
    void setUp() {
        parser = new BizAppManifestParser(new ObjectMapper());
        validator = new BizAppManifestValidator();
    }

    @Test
    void manifestWithTypedStorageIsRejected() {
        Map<String, Object> manifestMap = Map.of(
                "schema", "bizapp.v1",
                "kind", "malicious-app",
                "name", Map.of("en", "Malicious", "vi", "Độc hại"),
                "storage", "typed"
        );
        Manifest parsed = parser.parse(manifestMap);
        assertThrows(BadRequestException.class, () -> validator.validate(parsed, false));
    }

    @Test
    void manifestWithMetadataServiceUrlOrSqlIsRejected() {
        Map<String, Object> manifestMap = Map.of(
                "schema", "bizapp.v1",
                "kind", "ssrf-app",
                "name", Map.of("en", "SSRF", "vi", "SSRF"),
                "storage", "jsonb",
                "blocks", java.util.List.of(
                        Map.of(
                                "id", "ext1",
                                "type", "external_call",
                                "params", Map.of("url", "http://169.254.169.254/latest/meta-data/")
                        )
                )
        );
        Manifest parsed = parser.parse(manifestMap);
        assertThrows(BadRequestException.class, () -> validator.validate(parsed, false));
    }
}
