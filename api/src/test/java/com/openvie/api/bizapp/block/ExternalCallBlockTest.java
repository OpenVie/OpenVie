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

package com.openvie.api.bizapp.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.bizapp.service.BizAppCredentialCipher;
import com.openvie.api.bizapp.service.block.BizAppBlockExecutor;
import com.openvie.api.bizapp.service.block.ExternalCallBlock;
import com.openvie.api.common.net.SafeOutboundHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class ExternalCallBlockTest {

    private BizAppCredentialRepository credentialRepository;
    private BizAppCredentialCipher cipher;
    private SafeOutboundHttp outboundHttp;
    private ObjectMapper objectMapper;

    private ExternalCallBlock block;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        credentialRepository = mock(BizAppCredentialRepository.class);
        cipher = mock(BizAppCredentialCipher.class);
        outboundHttp = mock(SafeOutboundHttp.class);
        objectMapper = new ObjectMapper();

        when(cipher.available()).thenReturn(true);

        block = new ExternalCallBlock(credentialRepository, cipher, outboundHttp, objectMapper);
    }

    @Test
    void rendersBodyTemplateWithAllowedPlaceholdersAndIdempotencyKey() {
        UUID credId = UUID.randomUUID();
        BizAppCredential credential = new BizAppCredential();
        credential.setId(credId);
        credential.setOrgId(orgId);
        credential.setFormat("api_key");
        credential.setEncryptedData("cipher_bytes");

        when(credentialRepository.findByIdAndOrgId(credId, orgId)).thenReturn(Optional.of(credential));
        when(cipher.decrypt("cipher_bytes")).thenReturn("secret-provider-token");

        when(outboundHttp.execute(any())).thenReturn(new SafeOutboundHttp.Response(
                200, "{\"id\": \"env-123\", \"status\": \"sent\"}", Map.of()
        ));

        var spec = new BizAppBlockExecutor.BlockSpec(
                "sendToProvider",
                Map.of(
                        "url", "https://api.signing.test/v1/envelopes",
                        "credentialRef", credId.toString(),
                        "bodyTemplate", "{\"title\":\"{{record.title}}\",\"id\":\"{{record.id}}\",\"app\":\"{{app.kind}}\"}",
                        "responseMap", Map.of("envelopeId", "/id", "status", "/status")
                )
        );

        var context = new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "crm", "leads", recordId,
                Map.of("title", "Test Contract"),
                null,
                Map.of("allowedHosts", List.of("api.signing.test")),
                2
        );

        var result = block.execute(spec, context);

        ArgumentCaptor<SafeOutboundHttp.Request> reqCaptor = ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp).execute(reqCaptor.capture());

        var capturedReq = reqCaptor.getValue();
        assertEquals("secret-provider-token", capturedReq.headers().get("X-API-Key"));
        assertEquals(recordId + ":sendToProvider:2", capturedReq.headers().get("X-Idempotency-Key"));

        String body = capturedReq.body();
        assertTrue(body.contains("\"title\":\"Test Contract\""));
        assertTrue(body.contains("\"id\":\"" + recordId + "\""));
        assertTrue(body.contains("\"app\":\"crm\""));

        // Response extraction
        assertNotNull(result.runtimeUpdates());
        @SuppressWarnings("unchecked")
        Map<String, Object> external = (Map<String, Object>) result.runtimeUpdates().get("external");
        assertNotNull(external);
        @SuppressWarnings("unchecked")
        Map<String, Object> blockState = (Map<String, Object>) external.get("sendToProvider");
        assertEquals("env-123", blockState.get("envelopeId"));
        assertEquals("sent", blockState.get("status"));
    }

    @Test
    void rejectsUnsupportedPlaceholdersInBodyTemplate() {
        var spec = new BizAppBlockExecutor.BlockSpec(
                "sendToProvider",
                Map.of(
                        "url", "https://api.signing.test/v1",
                        "bodyTemplate", "{\"bad\":\"{{system.secret}}\"}"
                )
        );

        var context = new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "crm", "leads", recordId,
                Map.of(), null, Map.of("allowedHosts", List.of("api.signing.test")), 1
        );

        var result = block.execute(spec, context);
        assertTrue(result.summary().contains("failed"), result.summary());
        assertTrue(result.summary().contains("Unsupported placeholder"), result.summary());
    }

    @Test
    void failsSafelyWhenAllowedHostsNotConfigured() {
        when(outboundHttp.execute(any())).thenThrow(new com.openvie.api.common.exception.custom.BadRequestException(
                "Outbound call blocked: installation has no allowedHosts configured"
        ));

        var spec = new BizAppBlockExecutor.BlockSpec(
                "sendToProvider",
                Map.of("url", "https://api.signing.test/v1")
        );

        var context = new BizAppBlockExecutor.BlockContext(
                orgId, tenantId, actorId, "crm", "leads", recordId,
                Map.of(), null, Map.of(), 1 // empty config
        );

        var result = block.execute(spec, context);
        assertTrue(result.summary().contains("failed"), result.summary());
        assertTrue(result.summary().contains("no allowedHosts configured"), result.summary());
    }
}
