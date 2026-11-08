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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.common.net.SafeOutboundHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ConnectorOAuth2TokenCacheTest {

    private SafeOutboundHttp outboundHttp;
    private ConnectorOAuth2TokenCache cache;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        outboundHttp = mock(SafeOutboundHttp.class);
        cache = new ConnectorOAuth2TokenCache(outboundHttp, new ObjectMapper());
    }

    private ConnectorContext context() {
        return new ConnectorContext(orgId, tenantId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "ot-telemetry", "devices", UUID.randomUUID(), Map.of(),
                Map.of("allowedHosts", List.of("idp.test")));
    }

    private BizAppConnectorSpec spec() {
        return new BizAppConnectorSpecParser().parse(new Block("readTemp", "external_call", Map.of(
                "url", "https://ot-gateway.test/temp",
                "auth", Map.of("type", "oauth2",
                        "tokenUrl", "https://idp.test/token",
                        "credentialRef", "idp-client",
                        "scope", "ot.read"))));
    }

    @Test
    void requestsClientCredentialsAndCachesUntilTtl() {
        when(outboundHttp.execute(any())).thenReturn(new SafeOutboundHttp.Response(
                200, "{\"access_token\":\"token-1\",\"expires_in\":600}", Map.of()));

        String first = cache.token(spec(), context(), "client-id", "client-secret", List.of("idp.test"));
        String second = cache.token(spec(), context(), "client-id", "client-secret", List.of("idp.test"));

        assertEquals("token-1", first);
        assertEquals("token-1", second);
        ArgumentCaptor<SafeOutboundHttp.Request> captor =
                ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp, times(1)).execute(captor.capture());
        SafeOutboundHttp.Request request = captor.getValue();
        assertEquals("https://idp.test/token", request.url());
        assertEquals("POST", request.method());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "client-id:client-secret".getBytes(StandardCharsets.UTF_8)),
                request.headers().get("Authorization"));
        assertEquals("application/x-www-form-urlencoded",
                request.headers().get("Content-Type"));
        assertTrue(request.body().contains("grant_type=client_credentials"));
        assertTrue(request.body().contains("scope=ot.read"));
        assertEquals(10_000, request.connectTimeoutMs());
        assertEquals(15_000, request.readTimeoutMs());
    }

    @Test
    void rejectsTokenHostsOutsideTheInstallationAllowlist() {
        ConnectorException error = assertThrows(ConnectorException.class,
                () -> cache.token(spec(), context(), "client-id", "secret", List.of("other.test")));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("idp.test"));
    }

    @Test
    void classifiesTokenEndpointFailures() {
        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(500, "boom", Map.of()));
        assertTrue(assertThrows(ConnectorException.class,
                () -> cache.token(spec(), context(), "id", "secret", List.of("idp.test"))).retryable());

        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(400, "bad", Map.of()));
        assertFalse(assertThrows(ConnectorException.class,
                () -> cache.token(spec(), context(), "id", "secret", List.of("idp.test"))).retryable());

        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(200, "{\"token_type\":\"bearer\"}", Map.of()));
        assertFalse(assertThrows(ConnectorException.class,
                () -> cache.token(spec(), context(), "id", "secret", List.of("idp.test"))).retryable());
    }
}
