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

import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Field-level payload validation for BizApp records, driven by the manifest's
 * {@code entity.fields} schema: strict allow-list (unknown keys rejected),
 * type checks, enum options, email format, and length caps.
 */
@Component
public class BizAppPayloadValidator {

    public Map<String, Object> sanitize(Manifest manifest, String entityName, Map<String, Object> payload,
            boolean requireComplete) {
        Entity entity = manifest.entities().stream()
                .filter(candidate -> entityName.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown entity " + entityName));
        if (payload == null) {
            throw new BadRequestException("payload is required");
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        var declaredNames = entity.fields().stream().map(Field::name).collect(Collectors.toSet());
        for (String key : payload.keySet()) {
            if (!declaredNames.contains(key)) {
                throw new BadRequestException(
                        "payload contains field " + key + " outside the manifest entity definition");
            }
        }
        for (Field field : entity.fields()) {
            submitField(sanitized, entity, field, payload.get(field.name()),
                    requireComplete || payload.containsKey(field.name()));
        }
        return sanitized;
    }

    private void submitField(Map<String, Object> sanitized, Entity entity, Field field, Object value,
            boolean consider) {
        boolean required = Boolean.TRUE.equals(field.required());
        if (value == null || (value instanceof String text && text.isBlank())) {
            if (consider && required) {
                throw new BadRequestException("field " + field.name() + " requires a value");
            }
            return;
        }
        switch (field.type() == null ? "string" : field.type()) {
            case "number" -> sanitized.put(field.name(), number(entity, field, value));
            case "boolean" -> sanitized.put(field.name(), booleanValue(entity, field, value));
            case "date" -> sanitized.put(field.name(), dateValue(entity, field, value));
            case "enum" -> sanitized.put(field.name(), enumValue(entity, field, value));
            default -> sanitized.put(field.name(), text(entity, field, value));
        }
    }

    private Object text(Entity entity, Field field, Object value) {
        if (!(value instanceof String text)) {
            throw new BadRequestException(entityMessage(entity, field, "must be a string"));
        }
        int limit = field.maxLen() == null ? 1024 : field.maxLen();
        if (text.length() > limit) {
            throw new BadRequestException(entityMessage(entity, field, "exceeds max " + limit + " chars"));
        }
        if ("email".equals(field.format())) {
            int at = text.indexOf('@');
            if (at < 1 || at == text.length() - 1 || text.contains("..")) {
                throw new BadRequestException(entityMessage(entity, field, "must be an email address"));
            }
        }
        return text;
    }

    private Object number(Entity entity, Field field, Object value) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new BadRequestException(entityMessage(entity, field, "must be a number"));
        }
    }

    private Object booleanValue(Entity entity, Field field, Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new BadRequestException(entityMessage(entity, field, "must be a boolean"));
    }

    private Object dateValue(Entity entity, Field field, Object value) {
        try {
            return LocalDate.parse(String.valueOf(value)).toString();
        } catch (DateTimeParseException exception) {
            throw new BadRequestException(entityMessage(entity, field, "must be an ISO date (yyyy-MM-dd)"));
        }
    }

    private Object enumValue(Entity entity, Field field, Object value) {
        String candidate = String.valueOf(value);
        Optional<String> match = field.options() == null ? Optional.empty()
                : field.options().stream()
                        .filter(option -> option.equalsIgnoreCase(candidate))
                        .findFirst();
        if (match.isEmpty()) {
            throw new BadRequestException(entityMessage(entity, field, "must be one of " + field.options()));
        }
        return match.get();
    }

    private String entityMessage(Entity entity, Field field, String rule) {
        return "field " + entity.name() + "." + field.name() + " " + rule;
    }

    @SuppressWarnings("unused")
    private static String normalizeEnum(String value) {
        return value.toUpperCase(Locale.ROOT);
    }
}
