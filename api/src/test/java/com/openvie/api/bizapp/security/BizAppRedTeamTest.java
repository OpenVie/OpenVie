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

package com.openvie.api.bizapp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppDtos.CreateCredentialRequest;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.query.BizAppReportCompiler;
import com.openvie.api.bizapp.service.BizAppCredentialService;
import com.openvie.api.bizapp.service.BizAppManifestParser;
import com.openvie.api.bizapp.service.BizAppManifestValidator;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.net.SafeOutboundHttp;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class BizAppRedTeamTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BizAppManifestParser parser = new BizAppManifestParser(objectMapper);
    private final BizAppManifestValidator validator = new BizAppManifestValidator();
    private final SafeOutboundHttp outboundHttp = new SafeOutboundHttp(false);
    private final BizAppReportCompiler reportCompiler = new BizAppReportCompiler();

    private Map<String, Object> readFixture(String name) throws Exception {
        ClassPathResource res = new ClassPathResource("bizapp/redteam/" + name);
        try (InputStream is = res.getInputStream()) {
            return objectMapper.readValue(new String(is.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
        }
    }

    @Test
    void rejectsRawSqlInReportView() throws Exception {
        Map<String, Object> raw = readFixture("raw_sql.json");
        Manifest manifest = parser.parse(raw);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validate(manifest));
        assertTrue(ex.getMessage().contains("free-form sql"), ex.getMessage());
    }

    @Test
    void rejectsNonExistentFieldInReport() throws Exception {
        Map<String, Object> raw = readFixture("non_existent_field_report.json");
        Manifest manifest = parser.parse(raw);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validate(manifest));
        assertTrue(ex.getMessage().contains("groupBy field") || ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    void rejectsFilterFieldSqlInjectionAtValidationAndCompileTime() throws Exception {
        Map<String, Object> raw = readFixture("filter_field_sql_injection.json");
        Manifest manifest = parser.parse(raw);
        assertThrows(BadRequestException.class, () -> validator.validate(manifest));
    }

    @Test
    void rejectsExternalCallHttpScheme() {
        var req = new SafeOutboundHttp.Request(
                "http://internal-service.local/leak",
                "POST",
                Map.of(),
                null,
                List.of("internal-service.local")
        );
        BadRequestException ex = assertThrows(BadRequestException.class, () -> outboundHttp.execute(req));
        assertTrue(ex.getMessage().contains("HTTPS is required"), ex.getMessage());
    }

    @Test
    void rejectsExternalCallCloudMetadataSsrf() throws Exception {
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("169.254.169.254")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("127.0.0.1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("10.0.0.1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("192.168.0.1")));

        var req = new SafeOutboundHttp.Request(
                "https://169.254.169.254/latest/meta-data/",
                "GET",
                Map.of(),
                null,
                List.of("169.254.169.254")
        );
        BadRequestException ex = assertThrows(BadRequestException.class, () -> outboundHttp.execute(req));
        assertTrue(ex.getMessage().contains("restricted/private IP"), ex.getMessage());
    }

    @Test
    void rejectsStorageTypedForOrgTemplates() throws Exception {
        Map<String, Object> raw = readFixture("storage_typed_org_template.json");
        Manifest manifest = parser.parse(raw);
        // validate with isPlatformBundled=false (representing an org template, draft, or import)
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> validator.validate(manifest, false));
        assertTrue(ex.getMessage().contains("storage: typed is restricted"), ex.getMessage());
    }

    @Test
    void rejectsPemPrivateKeyInVault() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----";
        var request = new CreateCredentialRequest("sign-key", "api_key", pem, null, null);
        var mockRepo = org.mockito.Mockito.mock(com.openvie.api.bizapp.repository.BizAppCredentialRepository.class);
        var mockCipher = org.mockito.Mockito.mock(com.openvie.api.bizapp.service.BizAppCredentialCipher.class);
        org.mockito.Mockito.when(mockCipher.available()).thenReturn(true);
        var mockRuntime = org.mockito.Mockito.mock(com.openvie.api.bizapp.service.BizAppRuntime.class);
        var props = new com.openvie.api.bizapp.BizAppProperties(true, false, false, false, "");
        BizAppCredentialService service = new BizAppCredentialService(mockRepo, mockCipher, mockRuntime, props);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), request));
        assertTrue(ex.getMessage().contains("Private keys (PEM blocks) cannot be stored"), ex.getMessage());
    }

    @Test
    void rejectsOversizeManifestLimits() {
        // Create an entity with 45 fields (> max 40)
        var fields = new java.util.ArrayList<com.openvie.api.bizapp.dto.BizAppManifest.Field>();
        for (int i = 0; i < 45; i++) {
            fields.add(new com.openvie.api.bizapp.dto.BizAppManifest.Field("field" + i, "string", false, 50, null, null));
        }
        var entity = new com.openvie.api.bizapp.dto.BizAppManifest.Entity(
                "bloated", new com.openvie.api.bizapp.dto.BizAppManifest.Localized("B", "B"), fields, Map.of()
        );
        var manifest = new Manifest("bizapp.v1", "bloated-app",
                new com.openvie.api.bizapp.dto.BizAppManifest.Localized("B", "B"),
                1, "box", Map.of(), List.of(entity), List.of(), null);

        BadRequestException ex = assertThrows(BadRequestException.class, () -> validator.validate(manifest));
        assertTrue(ex.getMessage().contains("fields must be <= 40"), ex.getMessage());
    }
}
