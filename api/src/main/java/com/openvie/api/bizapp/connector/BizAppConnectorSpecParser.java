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

import com.openvie.api.bizapp.dto.BizAppManifest;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one place raw {@code external_call.params} becomes a typed
 * {@link BizAppConnectorSpec}. Install-time validation and runtime execution
 * both go through it, so manifest acceptance and job behavior can never drift.
 */
@Component
public class BizAppConnectorSpecParser {

    public static final Set<String> PROTOCOLS = Set.of("http", "mqtt");
    public static final Set<String> AUTH_TYPES = Set.of("header", "hmac", "oauth2");
    public static final Set<String> SINK_TYPES = Set.of("field", "record");
    private static final int MAX_RESPONSE_MAP_ENTRIES = 8;
    private static final Duration MIN_SCHEDULE = Duration.ofSeconds(30);
    private static final Duration MAX_SCHEDULE = Duration.ofDays(7);
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 60_000;

    public BizAppConnectorSpec parse(BizAppManifest.Block block) {
        Map<String, Object> params = block == null || block.params() == null ? Map.of() : block.params();

        String protocol = text(params, "protocol");
        protocol = protocol == null ? "http" : protocol.toLowerCase(Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) {
            throw new BadRequestException("external_call protocol must be http or mqtt");
        }

        String url = text(params, "url");
        String brokerUrl = text(params, "brokerUrl");
        String topic = text(params, "topic");
        if ("http".equals(protocol)) {
            if (url == null) {
                throw new BadRequestException("external_call (http) requires url");
            }
        } else {
            if (brokerUrl == null || topic == null) {
                throw new BadRequestException("external_call (mqtt) requires brokerUrl and topic");
            }
            java.net.URI broker = absolute(brokerUrl, "external_call brokerUrl must be an absolute URL");
            String brokerScheme = broker.getScheme() == null ? "" : broker.getScheme().toLowerCase(Locale.ROOT);
            if (!"ssl".equals(brokerScheme) && !"tcp".equals(brokerScheme)) {
                throw new BadRequestException("external_call brokerUrl must use ssl:// or tcp://");
            }
            if (topic.length() > 200 || topic.chars().anyMatch(Character::isWhitespace)) {
                throw new BadRequestException(
                        "external_call topic must be 1..200 characters without whitespace");
            }
        }

        if ("http".equals(protocol)) {
            absolute(url, "external_call url must be an absolute http(s) URL");
        }

        int qos = number(params, "qos", 1);
        if (qos < 0 || qos > 1) {
            throw new BadRequestException("external_call qos must be 0 or 1");
        }
        int maxMessages = number(params, "maxMessages", 1);
        if (maxMessages < 1 || maxMessages > 100) {
            throw new BadRequestException("external_call maxMessages must be 1..100");
        }

        Map<String, String> responseMap = pointerMap(
                params.get("responseMap"), "external_call responseMap pointers must start with /");
        if (responseMap.size() > MAX_RESPONSE_MAP_ENTRIES) {
            throw new BadRequestException("external_call responseMap supports at most "
                    + MAX_RESPONSE_MAP_ENTRIES + " entries");
        }

        return new BizAppConnectorSpec(
                protocol,
                url,
                text(params, "method") == null ? "POST" : text(params, "method"),
                text(params, "bodyTemplate"),
                responseMap,
                text(params, "credentialRef"),
                brokerUrl,
                topic,
                qos,
                maxMessages,
                auth(params.get("auth")),
                sink(params.get("sink")),
                schedule(params.get("schedule")),
                timeout(params.get("timeout")),
                retry(params.get("retry")),
                egress(params.get("egress")));
    }

    private BizAppConnectorSpec.Auth auth(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> auth = map(raw, "external_call auth must be an object");
        String type = text(auth, "type");
        type = type == null ? "header" : type.toLowerCase(Locale.ROOT);
        if (!AUTH_TYPES.contains(type)) {
            throw new BadRequestException("external_call auth.type must be header, hmac or oauth2");
        }
        return new BizAppConnectorSpec.Auth(
                type,
                text(auth, "secretRef"),
                text(auth, "header") == null ? "X-OpenVie-Signature" : text(auth, "header"),
                text(auth, "timestampHeader") == null
                        ? "X-OpenVie-Timestamp" : text(auth, "timestampHeader"),
                text(auth, "tokenUrl"),
                text(auth, "scope"),
                text(auth, "credentialRef"));
    }

    private BizAppConnectorSpec.Sink sink(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> sink = map(raw, "external_call sink must be an object");
        String type = text(sink, "type");
        type = type == null ? "field" : type.toLowerCase(Locale.ROOT);
        if (!SINK_TYPES.contains(type)) {
            throw new BadRequestException("external_call sink.type must be field or record");
        }
        Map<String, String> mapping = pointerMap(
                sink.get("mapping"), "external_call sink.mapping pointers must start with /");
        if (mapping.isEmpty()) {
            throw new BadRequestException("external_call sink requires a mapping");
        }
        List<String> dedupeKey = new ArrayList<>();
        Object rawKey = sink.get("dedupeKey");
        if (rawKey != null) {
            if (!(rawKey instanceof List<?> list)) {
                throw new BadRequestException("external_call sink.dedupeKey must be a subset of sink.mapping keys");
            }
            for (Object entry : list) {
                if (!(entry instanceof String field) || !mapping.containsKey(field)) {
                    throw new BadRequestException(
                            "external_call sink.dedupeKey must be a subset of sink.mapping keys");
                }
                dedupeKey.add(field);
            }
        }
        String entity = text(sink, "entity");
        if ("record".equals(type)) {
            if (entity == null) {
                throw new BadRequestException("external_call sink.entity is required");
            }
            if (dedupeKey.isEmpty() || dedupeKey.size() > 5) {
                throw new BadRequestException("external_call sink.dedupeKey must have 1..5 entries");
            }
        }
        return new BizAppConnectorSpec.Sink(type, entity, mapping, dedupeKey);
    }

    private BizAppConnectorSpec.Schedule schedule(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> schedule = map(raw, "external_call schedule must be an object");
        String everyText = text(schedule, "every");
        Duration every = null;
        if (everyText != null) {
            try {
                every = Duration.parse(everyText);
            } catch (DateTimeParseException exception) {
                every = null;
            }
        }
        if (every == null || every.compareTo(MIN_SCHEDULE) < 0 || every.compareTo(MAX_SCHEDULE) > 0) {
            throw new BadRequestException("external_call schedule.every must be between PT30S and P7D");
        }
        String entity = text(schedule, "entity");
        if (entity == null) {
            throw new BadRequestException("external_call schedule.entity is required");
        }
        Map<String, Object> select = new LinkedHashMap<>();
        if (schedule.get("select") != null) {
            Map<String, Object> rawSelect = map(schedule.get("select"),
                    "external_call schedule.select must be an object");
            if (rawSelect.size() > 5) {
                throw new BadRequestException(
                        "external_call schedule.select supports at most 5 declared fields");
            }
            for (Map.Entry<String, Object> entry : rawSelect.entrySet()) {
                Object value = entry.getValue();
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw new BadRequestException(
                            "external_call schedule.select values must be string, number or boolean");
                }
                select.put(entry.getKey(), value);
            }
        }
        int limit = number(schedule, "limit", 50);
        if (limit < 1 || limit > 500) {
            throw new BadRequestException("external_call schedule.limit must be 1..500");
        }
        return new BizAppConnectorSpec.Schedule(every, entity, select, limit);
    }

    private BizAppConnectorSpec.Timeout timeout(Object raw) {
        if (raw == null) {
            return new BizAppConnectorSpec.Timeout(10_000, 15_000);
        }
        Map<String, Object> timeout = map(raw, "external_call timeout must be an object");
        int connectMs = number(timeout, "connectMs", 10_000);
        int readMs = number(timeout, "readMs", 15_000);
        if (connectMs < MIN_TIMEOUT_MS || connectMs > MAX_TIMEOUT_MS
                || readMs < MIN_TIMEOUT_MS || readMs > MAX_TIMEOUT_MS) {
            throw new BadRequestException("external_call timeout values must be 100..60000 ms");
        }
        return new BizAppConnectorSpec.Timeout(connectMs, readMs);
    }

    private BizAppConnectorSpec.Retry retry(Object raw) {
        if (raw == null) {
            return new BizAppConnectorSpec.Retry(3, Duration.ofSeconds(10));
        }
        Map<String, Object> retry = map(raw, "external_call retry must be an object");
        int maxAttempts = number(retry, "maxAttempts", 3);
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new BadRequestException("external_call retry.maxAttempts must be 1..10");
        }
        String backoffText = text(retry, "backoff");
        Duration backoff = Duration.ofSeconds(10);
        if (backoffText != null) {
            try {
                backoff = Duration.parse(backoffText);
            } catch (DateTimeParseException exception) {
                backoff = null;
            }
        }
        if (backoff == null || backoff.compareTo(MIN_BACKOFF) < 0
                || backoff.compareTo(MAX_BACKOFF) > 0) {
            throw new BadRequestException("external_call retry.backoff must be between PT1S and PT10M");
        }
        return new BizAppConnectorSpec.Retry(maxAttempts, backoff);
    }

    private List<String> egress(Object raw) {
        if (raw == null) {
            return List.of();
        }
        Map<String, Object> egress = map(raw, "external_call egress must be an object");
        Object hosts = egress.get("allowedHosts");
        if (hosts == null) {
            return List.of();
        }
        if (!(hosts instanceof List<?> list)) {
            throw new BadRequestException("external_call egress.allowedHosts must be a list of hosts");
        }
        List<String> result = new ArrayList<>();
        for (Object host : list) {
            if (!(host instanceof String text)) {
                throw new BadRequestException("external_call egress.allowedHosts must be a list of hosts");
            }
            result.add(text.trim());
        }
        return result;
    }

    /** JSON-pointer map (`target -> /pointer`). */
    private Map<String, String> pointerMap(Object raw, String invalidPointerMessage) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> map = map(raw, invalidPointerMessage);
        Map<String, String> pointers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String pointer) || !pointer.startsWith("/")) {
                throw new BadRequestException(invalidPointerMessage);
            }
            pointers.put(entry.getKey(), pointer);
        }
        return pointers;
    }

    private java.net.URI absolute(String value, String message) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BadRequestException(message);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(message);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw, String message) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new BadRequestException(message);
    }

    private int number(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new BadRequestException("external_call " + key + " must be a number");
        }
    }

    private String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
