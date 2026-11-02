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

package com.openvie.api.bizapp.service.block;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.bizapp.model.BizAppCredential;
import com.openvie.api.bizapp.repository.BizAppCredentialRepository;
import com.openvie.api.bizapp.service.BizAppCredentialCipher;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.InternalServerErrorException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.common.net.SafeOutboundHttp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardened outbound HTTP block with SSRF defense (via {@link SafeOutboundHttp}),
 * credential vault resolution by id or name, body templating, idempotency headers,
 * and response extraction.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-BLOCK")
public class ExternalCallBlock implements BizAppBlockExecutor {

    public static final String TYPE = "external_call";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final BizAppCredentialRepository credentialRepository;
    private final BizAppCredentialCipher cipher;
    private final SafeOutboundHttp outboundHttp;
    private final ObjectMapper objectMapper;

    @Override
    public String supports() {
        return TYPE;
    }

    @Override
    public BlockResult execute(BlockSpec spec, BlockContext context) {
        String url = string(spec.params(), "url");
        if (url == null) {
            throw new BadRequestException("external_call block " + spec.blockId() + " has no url");
        }

        try {
            // 1. Resolve credentials
            Map<String, String> headers = new HashMap<>();
            applyCredential(headers, spec, context);

            // 2. Set Idempotency-Key
            String idempotencyKey = (context.recordId() != null ? context.recordId() : UUID.randomUUID())
                    + ":" + spec.blockId() + ":" + (context.version() != null ? context.version() : 1);
            headers.put("X-Idempotency-Key", idempotencyKey);
            headers.put("Content-Type", "application/json");

            // 3. Render body
            String requestBody = renderBody(spec, context);

            // 4. Extract allowedHosts from installation config
            List<String> allowedHosts = extractAllowedHosts(context.config());

            String method = string(spec.params(), "method");
            if (method == null || method.isBlank()) {
                method = "POST";
            }

            var request = new SafeOutboundHttp.Request(
                    url,
                    method,
                    headers,
                    requestBody,
                    allowedHosts
            );

            // 5. Execute safe outbound call
            SafeOutboundHttp.Response response = outboundHttp.execute(request);
            int status = response.statusCode();

            // 6. Response mapping
            Map<String, Object> runtimeUpdates = new LinkedHashMap<>();
            Map<String, Object> extractedMap = extractResponse(spec, response.body());
            if (!extractedMap.isEmpty()) {
                Map<String, Object> existingExternal = new LinkedHashMap<>();
                runtimeUpdates.put("external", Map.of(spec.blockId(), extractedMap));
            }

            return new BlockResult("external_call " + spec.blockId() + " -> HTTP " + status, runtimeUpdates);

        } catch (ResourceNotFoundException exception) {
            log.warn("external_call {} failed: credential unavailable: {}", spec.blockId(), exception.getMessage());
            return new BlockResult("external_call " + spec.blockId() + " failed: credential unavailable: " + exception.getMessage());
        } catch (BadRequestException exception) {
            log.warn("external_call {} rejected: {}", spec.blockId(), exception.getMessage());
            return new BlockResult("external_call " + spec.blockId() + " failed: " + exception.getMessage());
        } catch (Exception exception) {
            log.warn("external_call {} failed: {}", spec.blockId(), exception.getMessage());
            return new BlockResult("external_call " + spec.blockId() + " failed: " + exception.getMessage());
        }
    }

    private void applyCredential(Map<String, String> headers, BlockSpec spec, BlockContext context) {
        String credentialRef = string(spec.params(), "credentialRef");
        if (credentialRef == null || credentialRef.isBlank()) {
            return;
        }
        if (!cipher.available()) {
            throw new InternalServerErrorException(
                    "Stored BizApp credentials require BIZAPP_ENC_KEY (32 random bytes, base64).");
        }
        BizAppCredential credential = resolveCredential(context.orgId(), credentialRef);
        String plaintext = cipher.decrypt(credential.getEncryptedData());

        switch (credential.getFormat()) {
            case "api_key" -> headers.put("X-API-Key", plaintext);
            case "bearer" -> headers.put("Authorization", "Bearer " + plaintext);
            case "basic" -> headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8)));
            default -> throw new BadRequestException("Unsupported credential format " + credential.getFormat());
        }
    }

    private BizAppCredential resolveCredential(UUID orgId, String credentialRef) {
        BizAppCredential credential = null;
        try {
            UUID id = UUID.fromString(credentialRef.trim());
            credential = credentialRepository.findByIdAndOrgId(id, orgId).orElse(null);
        } catch (IllegalArgumentException ignored) {
            // try by name
        }
        if (credential == null) {
            credential = credentialRepository.findByOrgIdAndName(orgId, credentialRef.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Credential " + credentialRef + " not found in org vault"));
        }
        return credential;
    }

    private String renderBody(BlockSpec spec, BlockContext context) {
        String bodyTemplate = string(spec.params(), "bodyTemplate");
        if (bodyTemplate != null && !bodyTemplate.isBlank()) {
            return substitutePlaceholders(bodyTemplate, context);
        }

        // Fallback standard JSON body
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("app", context.appKind());
        fallback.put("entity", context.entity());
        if (context.recordId() != null) {
            fallback.put("record_id", context.recordId().toString());
        }
        if (context.payload() != null) {
            fallback.put("record", context.payload());
        }
        try {
            return objectMapper.writeValueAsString(fallback);
        } catch (Exception ex) {
            throw new BadRequestException("Failed to serialize request body: " + ex.getMessage());
        }
    }

    private String substitutePlaceholders(String template, BlockContext context) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String value;
            if ("record.id".equals(token)) {
                value = context.recordId() != null ? context.recordId().toString() : "";
            } else if ("app.kind".equals(token)) {
                value = context.appKind() != null ? context.appKind() : "";
            } else if (token.startsWith("record.")) {
                String field = token.substring("record.".length());
                Object val = context.payload() != null ? context.payload().get(field) : null;
                value = val != null ? String.valueOf(val) : "";
            } else {
                throw new BadRequestException("Unsupported placeholder in bodyTemplate: " + token);
            }
            // Escape for JSON string inclusion
            String escaped = escapeJson(value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(escaped));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private Map<String, Object> extractResponse(BlockSpec spec, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        Object responseMapObj = spec.params() != null ? spec.params().get("responseMap") : null;
        if (!(responseMapObj instanceof Map<?, ?> responseMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int count = 0;
            for (Map.Entry<?, ?> entry : responseMap.entrySet()) {
                if (count >= 8) break; // cap at 8 keys
                String targetKey = String.valueOf(entry.getKey());
                String pointer = String.valueOf(entry.getValue());
                JsonNode targetNode = root.at(pointer);
                if (!targetNode.isMissingNode()) {
                    String textVal = targetNode.asText();
                    if (textVal.length() > 200) {
                        textVal = textVal.substring(0, 200); // cap at 200 chars
                    }
                    result.put(targetKey, textVal);
                    count++;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to parse response for responseMap extraction: {}", ex.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAllowedHosts(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object hosts = config.get("allowedHosts");
        if (hosts instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object h : list) {
                if (h != null) result.add(String.valueOf(h));
            }
            return result;
        }
        return List.of();
    }

    private String string(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
