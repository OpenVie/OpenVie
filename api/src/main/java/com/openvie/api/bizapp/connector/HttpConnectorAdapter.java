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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.common.net.SafeOutboundHttp;
import com.openvie.api.integration.webhook.WebhookEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP(S) connector transport: body templating, vault header auth, HMAC request
 * signing, OAuth2 bearer tokens, and the installation egress allowlist.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class HttpConnectorAdapter implements ConnectorAdapter {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final SafeOutboundHttp outboundHttp;
    private final ConnectorCredentials credentials;
    private final ConnectorOAuth2TokenCache tokenCache;
    private final ObjectMapper objectMapper;

    @Override
    public String protocol() {
        return "http";
    }

    @Override
    public ConnectorResponse execute(BizAppConnectorSpec spec, ConnectorContext context) {
        String url = spec.url();
        String host = host(url);
        List<String> installationHosts = installationHosts(context);
        List<String> effectiveHosts = effectiveHosts(spec, installationHosts);
        if (host == null || effectiveHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw ConnectorException.permanent(
                    "connector host " + host + " is not allowed for this installation");
        }

        String method = spec.method() == null ? "POST" : spec.method().toUpperCase(Locale.ROOT);
        // HttpURLConnection turns a GET with an output stream into a POST, so a
        // declared GET must carry no body at all. GET-only APIs (weather,
        // health, readings feeds) would otherwise receive an unexpected POST.
        boolean bodyless = "GET".equals(method) || "HEAD".equals(method);
        String body = bodyless ? null : renderBody(spec, context);
        Map<String, String> headers = new HashMap<>();
        if (!bodyless) {
            headers.put("Content-Type", "application/json");
        }
        headers.put("X-Idempotency-Key", idempotencyKey(context));
        applyAuth(spec, context, installationHosts, body == null ? "" : body, headers);

        SafeOutboundHttp.Response response = outboundHttp.execute(new SafeOutboundHttp.Request(
                url,
                method,
                headers,
                body,
                effectiveHosts,
                spec.timeout().connectMs(),
                spec.timeout().readMs()));

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = "HTTP " + status + " from " + host;
            if (status == 408 || status == 429 || status >= 500) {
                throw ConnectorException.retryable(message);
            }
            throw ConnectorException.permanent(message);
        }
        return new ConnectorResponse(status, response.body(), host);
    }

    private void applyAuth(BizAppConnectorSpec spec, ConnectorContext context,
            List<String> installationHosts, String body, Map<String, String> headers) {
        BizAppConnectorSpec.Auth auth = spec.auth();
        String type = auth == null ? "header" : auth.type();
        switch (type) {
            case "hmac" -> {
                ConnectorCredentials.Resolved resolved = credentials.resolve(
                        context.orgId(), context.tenantId(), auth.secretRef());
                requireFormat(resolved, "hmac");
                String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
                headers.put(auth.timestampHeader(), timestamp);
                headers.put(auth.header(),
                        WebhookEventPublisher.signatureHeader(resolved.plaintext(), timestamp, body));
            }
            case "oauth2" -> {
                ConnectorCredentials.Resolved resolved = credentials.resolve(
                        context.orgId(), context.tenantId(), auth.credentialRef());
                requireFormat(resolved, "oauth2");
                String[] pair = splitPair(resolved);
                String token = tokenCache.token(spec, context, pair[0], pair[1], installationHosts);
                headers.put("Authorization", "Bearer " + token);
            }
            default -> {
                if (spec.credentialRef() == null) {
                    throw ConnectorException.permanent(
                            "external_call (http) requires credentialRef");
                }
                ConnectorCredentials.Resolved resolved = credentials.resolve(
                        context.orgId(), context.tenantId(), spec.credentialRef());
                switch (resolved.format()) {
                    case "api_key" -> headers.put("X-API-Key", resolved.plaintext());
                    case "bearer" -> headers.put("Authorization", "Bearer " + resolved.plaintext());
                    case "basic" -> headers.put("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(resolved.plaintext().getBytes(StandardCharsets.UTF_8)));
                    default -> throw ConnectorException.permanent(
                            "credential format " + resolved.format()
                                    + " cannot be used with auth.type header");
                }
            }
        }
    }

    private void requireFormat(ConnectorCredentials.Resolved resolved, String expected) {
        if (resolved == null || !expected.equals(resolved.format())) {
            throw ConnectorException.permanent(
                    "credential format " + (resolved == null ? "missing" : resolved.format())
                            + " is not " + expected);
        }
    }

    private String[] splitPair(ConnectorCredentials.Resolved resolved) {
        String plaintext = resolved.plaintext() == null ? "" : resolved.plaintext();
        int separator = plaintext.indexOf(':');
        if (separator < 1) {
            throw ConnectorException.permanent("oauth2 credential must be clientId:clientSecret");
        }
        return new String[]{plaintext.substring(0, separator), plaintext.substring(separator + 1)};
    }

    private String renderBody(BizAppConnectorSpec spec, ConnectorContext context) {
        if (spec.bodyTemplate() != null && !spec.bodyTemplate().isBlank()) {
            return substitutePlaceholders(spec.bodyTemplate(), context);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("app", context.appKind());
        fallback.put("entity", context.entity());
        if (context.recordId() != null) {
            fallback.put("record_id", context.recordId().toString());
        }
        if (context.recordPayload() != null) {
            fallback.put("record", context.recordPayload());
        }
        try {
            return objectMapper.writeValueAsString(fallback);
        } catch (Exception exception) {
            throw ConnectorException.permanent(
                    "Failed to serialize request body: " + exception.getMessage());
        }
    }

    private String substitutePlaceholders(String template, ConnectorContext context) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String value;
            if ("record.id".equals(token)) {
                value = context.recordId() != null ? context.recordId().toString() : "";
            } else if ("app.kind".equals(token)) {
                value = context.appKind() != null ? context.appKind() : "";
            } else if (token.startsWith("record.")) {
                String field = token.substring("record.".length());
                Object raw = context.recordPayload() == null ? null : context.recordPayload().get(field);
                value = raw != null ? String.valueOf(raw) : "";
            } else {
                throw ConnectorException.permanent("Unsupported placeholder in bodyTemplate: " + token);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(escapeJson(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String idempotencyKey(ConnectorContext context) {
        return context.jobId() != null
                ? context.jobId().toString()
                : String.valueOf(context.recordId());
    }

    @SuppressWarnings("unchecked")
    private List<String> installationHosts(ConnectorContext context) {
        Object hosts = context.installationConfig().get("allowedHosts");
        if (!(hosts instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object host : list) {
            if (host != null) {
                result.add(String.valueOf(host).trim());
            }
        }
        return result;
    }

    private List<String> effectiveHosts(BizAppConnectorSpec spec, List<String> installationHosts) {
        if (spec.egressHosts().isEmpty()) {
            return installationHosts;
        }
        List<String> intersection = new ArrayList<>();
        for (String host : spec.egressHosts()) {
            if (installationHosts.stream().anyMatch(host::equalsIgnoreCase)) {
                intersection.add(host);
            }
        }
        return intersection;
    }

    private String host(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
