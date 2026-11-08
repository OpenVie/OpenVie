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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.common.net.SafeOutboundHttp;
import com.openvie.api.integration.webhook.WebhookEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class HttpConnectorAdapterTest {

    private SafeOutboundHttp outboundHttp;
    private ConnectorCredentials credentials;
    private ConnectorOAuth2TokenCache tokenCache;
    private HttpConnectorAdapter adapter;

    private final UUID orgId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        outboundHttp = mock(SafeOutboundHttp.class);
        credentials = mock(ConnectorCredentials.class);
        tokenCache = mock(ConnectorOAuth2TokenCache.class);
        adapter = new HttpConnectorAdapter(outboundHttp, credentials, tokenCache, new ObjectMapper());
    }

    private ConnectorContext context() {
        return new ConnectorContext(orgId, tenantId, UUID.randomUUID(), UUID.randomUUID(), jobId,
                "ot-telemetry", "devices", recordId, Map.of("title", "Boiler 1"),
                Map.of("allowedHosts", List.of("ot-gateway.test")));
    }

    private static BizAppConnectorSpec spec(Map<String, Object> params) {
        return new BizAppConnectorSpecParser().parse(new Block("readTemp", "external_call", params));
    }

    private SafeOutboundHttp.Request captureRequest(SafeOutboundHttp.Response response) {
        when(outboundHttp.execute(any())).thenReturn(response);
        ArgumentCaptor<SafeOutboundHttp.Request> captor =
                ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp).execute(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendsTimeoutsIdempotencyKeyAndVaultApiKey() {
        when(credentials.resolve(orgId, tenantId, "gateway-key"))
                .thenReturn(new ConnectorCredentials.Resolved("api_key", "secret-token"));
        var spec = spec(Map.of(
                "url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key",
                "timeout", Map.of("connectMs", 2_000, "readMs", 3_000)));
        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(200, "{\"temp\":42}", Map.of()));

        adapter.execute(spec, context());

        ArgumentCaptor<SafeOutboundHttp.Request> captor =
                ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp).execute(captor.capture());
        SafeOutboundHttp.Request request = captor.getValue();
        assertEquals(2_000, request.connectTimeoutMs());
        assertEquals(3_000, request.readTimeoutMs());
        assertEquals(jobId.toString(), request.headers().get("X-Idempotency-Key"));
        assertEquals("secret-token", request.headers().get("X-API-Key"));
        assertEquals(List.of("ot-gateway.test"), request.allowedHosts());
    }

    @Test
    void signsTheBodyWithHmacAndTimestampHeaders() {
        when(credentials.resolve(orgId, tenantId, "signing-secret"))
                .thenReturn(new ConnectorCredentials.Resolved("hmac", "shared-secret"));
        var spec = spec(Map.of(
                "url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key",
                "bodyTemplate", "{\"id\":\"{{record.id}}\",\"app\":\"{{app.kind}}\"}",
                "auth", Map.of("type", "hmac", "secretRef", "signing-secret")));
        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(200, "{}", Map.of()));

        adapter.execute(spec, context());

        ArgumentCaptor<SafeOutboundHttp.Request> captor =
                ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp).execute(captor.capture());
        SafeOutboundHttp.Request request = captor.getValue();
        String timestamp = request.headers().get("X-OpenVie-Timestamp");
        assertTrue(timestamp.matches("\\d+"));
        assertEquals(WebhookEventPublisher.signatureHeader("shared-secret", timestamp, request.body()),
                request.headers().get("X-OpenVie-Signature"));
    }

    @Test
    void sendsOauth2BearerFromTheTokenCache() {
        when(credentials.resolve(orgId, tenantId, "idp-client"))
                .thenReturn(new ConnectorCredentials.Resolved("oauth2", "client-id:client-secret"));
        var spec = spec(Map.of(
                "url", "https://ot-gateway.test/temp",
                "auth", Map.of("type", "oauth2",
                        "tokenUrl", "https://idp.test/token",
                        "credentialRef", "idp-client")));
        when(tokenCache.token(eq(spec), any(), eq("client-id"), eq("client-secret"), any()))
                .thenReturn("access-token-1");
        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(200, "{}", Map.of()));

        adapter.execute(spec, context());

        ArgumentCaptor<SafeOutboundHttp.Request> captor =
                ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp).execute(captor.capture());
        assertEquals("Bearer access-token-1",
                captor.getValue().headers().get("Authorization"));
    }

    @Test
    void classifiesHttpStatuses() {
        var spec = spec(Map.of("url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key"));
        when(credentials.resolve(any(), any(), any()))
                .thenReturn(new ConnectorCredentials.Resolved("bearer", "token"));

        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(404, "missing", Map.of()));
        ConnectorException notFound = assertThrows(ConnectorException.class,
                () -> adapter.execute(spec, context()));
        assertFalse(notFound.retryable());
        assertEquals("HTTP 404 from ot-gateway.test", notFound.getMessage());

        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(503, "busy", Map.of()));
        ConnectorException unavailable = assertThrows(ConnectorException.class,
                () -> adapter.execute(spec, context()));
        assertTrue(unavailable.retryable());
        assertEquals("HTTP 503 from ot-gateway.test", unavailable.getMessage());
    }

    @Test
    void rejectsHostsOutsideTheInstallationEgressIntersection() {
        var spec = spec(Map.of(
                "url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key",
                "egress", Map.of("allowedHosts", List.of("other-gateway.test"))));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> adapter.execute(spec, context()));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("ot-gateway.test"));
    }

    @Test
    void rejectsCredentialFormatMismatchForHeaderAuth() {
        when(credentials.resolve(any(), any(), any()))
                .thenReturn(new ConnectorCredentials.Resolved("mqtt", "user:pass"));
        var spec = spec(Map.of("url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key"));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> adapter.execute(spec, context()));
        assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("cannot be used with auth.type header"));
    }

    @Test
    void rendersPlaceholdersAndFallsBackToTheRecordBody() {
        when(credentials.resolve(any(), any(), any()))
                .thenReturn(new ConnectorCredentials.Resolved("basic", "user:pass"));
        var spec = spec(Map.of(
                "url", "https://ot-gateway.test/temp",
                "credentialRef", "gateway-key",
                "bodyTemplate", "{\"title\":\"{{record.title}}\",\"id\":\"{{record.id}}\"}"));
        when(outboundHttp.execute(any())).thenReturn(
                new SafeOutboundHttp.Response(200, "{}", Map.of()));

        adapter.execute(spec, context());

        ArgumentCaptor<SafeOutboundHttp.Request> captor =
                ArgumentCaptor.forClass(SafeOutboundHttp.Request.class);
        verify(outboundHttp).execute(captor.capture());
        String body = captor.getValue().body();
        assertTrue(body.contains("\"title\":\"Boiler 1\""));
        assertTrue(body.contains("\"id\":\"" + recordId + "\""));
        assertTrue(captor.getValue().headers().get("Authorization").startsWith("Basic "));
    }
}
