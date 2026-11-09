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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.common.net.SafeOutboundHttp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 client-credentials tokens for connector calls, cached per credential
 * until 80% of the provider TTL so a poll loop does not mint a token per job.
 *
 * <p>{@code client_secret_basic} only: the token endpoint receives the client
 * pair in the Authorization header, never in the request body.
 */
@Component
@RequiredArgsConstructor
public class ConnectorOAuth2TokenCache {

    private static final int DEFAULT_EXPIRES_IN_SECONDS = 300;
    private static final double REFRESH_AT = 0.8;

    private final SafeOutboundHttp outboundHttp;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    private record CachedToken(String token, long expiresAtNanos) {
        boolean valid() {
            return System.nanoTime() < expiresAtNanos;
        }
    }

    public String token(BizAppConnectorSpec spec, ConnectorContext context,
            String clientId, String clientSecret, List<String> installationHosts) {
        BizAppConnectorSpec.Auth auth = spec.auth();
        if (auth == null || auth.tokenUrl() == null) {
            throw ConnectorException.permanent("external_call auth oauth2 requires an https tokenUrl");
        }
        String tokenUrl = auth.tokenUrl();
        String host = host(tokenUrl);
        if (host == null || installationHosts == null
                || installationHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw ConnectorException.permanent(
                    "oauth2 token host " + host + " is not allowed for this installation");
        }
        String scope = auth.scope() == null ? "" : auth.scope();
        String key = String.join("|",
                String.valueOf(context.orgId()), String.valueOf(context.tenantId()),
                String.valueOf(auth.credentialRef()), tokenUrl, scope);
        CachedToken cached = cache.get(key);
        if (cached != null && cached.valid()) {
            return cached.token();
        }
        CachedToken fresh = request(spec, tokenUrl, clientId, clientSecret, scope, installationHosts);
        cache.put(key, fresh);
        return fresh.token();
    }

    private CachedToken request(BizAppConnectorSpec spec, String tokenUrl,
            String clientId, String clientSecret, String scope, List<String> installationHosts) {
        StringBuilder body = new StringBuilder("grant_type=client_credentials");
        if (!scope.isBlank()) {
            body.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        Map<String, String> headers = Map.of(
                "Content-Type", "application/x-www-form-urlencoded",
                "Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));
        SafeOutboundHttp.Response response = outboundHttp.execute(new SafeOutboundHttp.Request(
                tokenUrl, "POST", headers, body.toString(), installationHosts,
                spec.timeout().connectMs(), spec.timeout().readMs()));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "oauth2 token endpoint answered HTTP " + response.statusCode();
            if (response.statusCode() == 408 || response.statusCode() == 429
                    || response.statusCode() >= 500) {
                throw ConnectorException.retryable(message);
            }
            throw ConnectorException.permanent(message);
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(response.body());
        } catch (Exception exception) {
            throw ConnectorException.permanent("oauth2 token response is not JSON");
        }
        String token = json.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw ConnectorException.permanent("oauth2 token response has no access_token");
        }
        int expiresIn = json.path("expires_in").asInt(DEFAULT_EXPIRES_IN_SECONDS);
        if (expiresIn <= 0) {
            expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        }
        long ttlNanos = (long) ((double) expiresIn * REFRESH_AT * 1_000_000_000L);
        return new CachedToken(token, System.nanoTime() + ttlNanos);
    }

    private String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
