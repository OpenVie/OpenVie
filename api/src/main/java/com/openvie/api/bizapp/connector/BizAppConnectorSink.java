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
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.model.BizAppRecord;
import com.openvie.api.bizapp.repository.BizAppRecordRepository;
import com.openvie.api.bizapp.service.BizAppPayloadValidator;
import com.openvie.api.bizapp.service.storage.BizAppStorageRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a connector response into manifest-declared fields.
 *
 * <p>Two shapes: {@code field} updates the triggering record in place, and
 * {@code record} appends to (or updates) one record per declared dedupe
 * identity, so a poll loop can run forever without duplicating telemetry rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-CONNECTOR")
public class BizAppConnectorSink {

    private static final int MAX_DEDUPE_KEY_LENGTH = 64;

    private final BizAppPayloadValidator payloadValidator;
    private final BizAppRecordRepository recordRepository;
    private final BizAppStorageRouter storageRouter;
    private final ObjectMapper objectMapper;

    /**
     * @return true when at least one declared value was written; false keeps
     *         the job summary honest ("no mapped values") without failing it.
     */
    public boolean apply(BizAppInstallation installation, Manifest manifest,
            BizAppConnectorSpec.Sink sink, ConnectorResponse response,
            BizAppRecord sourceRecord, UUID actorId) {
        if (sink == null) {
            return false;
        }
        String targetEntity = "field".equals(sink.type()) ? sourceRecord.getEntity() : sink.entity();
        Map<String, Object> mapped = map(manifest, targetEntity, sink, response.body());
        if (mapped.isEmpty()) {
            return false;
        }
        if ("field".equals(sink.type())) {
            Map<String, Object> merged = new LinkedHashMap<>(
                    sourceRecord.getPayload() == null ? Map.of() : sourceRecord.getPayload());
            merged.putAll(mapped);
            sourceRecord.setPayload(payloadValidator.sanitize(
                    manifest, sourceRecord.getEntity(), merged, false));
            return true;
        }
        appendRecord(manifest, sink, mapped, sourceRecord, actorId);
        return true;
    }

    private void appendRecord(Manifest manifest, BizAppConnectorSpec.Sink sink,
            Map<String, Object> mapped, BizAppRecord sourceRecord, UUID actorId) {
        Map<String, Object> clean = payloadValidator.sanitize(manifest, sink.entity(), mapped, false);
        for (String field : sink.dedupeKey()) {
            Object value = clean.get(field);
            if (value == null || (value instanceof String text && text.isBlank())) {
                throw ConnectorException.permanent(
                        "dedupe field " + field + " missing from connector response");
            }
        }
        String dedupeKey = dedupeHash(sink.dedupeKey(), clean);
        recordRepository.lockDedupeKey(sourceRecord.getTenantId() + "|"
                + sourceRecord.getAppKind() + "|" + sink.entity() + "|" + dedupeKey);

        BizAppRecord existing = recordRepository
                .findByTenantIdAndAppKindAndEntityAndDedupeKey(sourceRecord.getTenantId(),
                        sourceRecord.getAppKind(), sink.entity(), dedupeKey)
                .orElse(null);
        if (existing != null) {
            Map<String, Object> merged = new LinkedHashMap<>(
                    existing.getPayload() == null ? Map.of() : existing.getPayload());
            merged.putAll(clean);
            existing.setPayload(payloadValidator.sanitize(manifest, sink.entity(), merged, false));
            existing.setUpdatedBy(actorId);
            storageRouter.route(sourceRecord.getAppKind(), sink.entity()).save(existing);
            return;
        }
        BizAppRecord created = new BizAppRecord();
        created.setOrgId(sourceRecord.getOrgId());
        created.setTenantId(sourceRecord.getTenantId());
        created.setAppKind(sourceRecord.getAppKind());
        created.setEntity(sink.entity());
        created.setPayload(clean);
        created.setDedupeKey(dedupeKey);
        created.setRuntime(Map.of());
        created.setStatus("ACTIVE");
        created.setCreatedBy(actorId);
        storageRouter.route(sourceRecord.getAppKind(), sink.entity()).save(created);
    }

    /** Stable across field order: `field=value;` sorted, SHA-256, hex (64 chars). */
    static String dedupeHash(List<String> fields, Map<String, Object> values) {
        List<String> entries = new ArrayList<>();
        for (String field : fields) {
            entries.add(field + "=" + values.get(field) + ";");
        }
        entries.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("", entries).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, MAX_DEDUPE_KEY_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for connector dedupe keys", exception);
        }
    }

    private Map<String, Object> map(Manifest manifest, String targetEntity,
            BizAppConnectorSpec.Sink sink, String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw ConnectorException.permanent("connector response is not valid JSON");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sink.mapping().entrySet()) {
            JsonNode node = root.at(entry.getValue());
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            String fieldType = manifest.entityField(targetEntity, entry.getKey());
            if (fieldType == null) {
                throw ConnectorException.permanent(
                        "external_call sink.mapping target field " + entry.getKey()
                                + " is not declared on " + targetEntity);
            }
            mapped.put(entry.getKey(), coerce(node, fieldType));
        }
        return mapped;
    }

    private Object coerce(JsonNode node, String fieldType) {
        switch (fieldType) {
            case "boolean" -> {
                if (node.isBoolean()) {
                    return node.booleanValue();
                }
                if (node.isTextual()) {
                    String text = node.textValue().trim().toLowerCase(java.util.Locale.ROOT);
                    if ("true".equals(text) || "1".equals(text)) {
                        return Boolean.TRUE;
                    }
                    if ("false".equals(text) || "0".equals(text)) {
                        return Boolean.FALSE;
                    }
                }
                return node.isTextual() ? node.textValue() : node.asBoolean();
            }
            case "number" -> {
                if (node.isNumber()) {
                    return node.decimalValue();
                }
                if (node.isTextual()) {
                    try {
                        return new BigDecimal(node.textValue().trim());
                    } catch (NumberFormatException invalid) {
                        return node.textValue();
                    }
                }
                return node.asText();
            }
            default -> {
                return node.isTextual() ? node.textValue() : node.asText();
            }
        }
    }
}
