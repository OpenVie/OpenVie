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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        if (value == null) return null;
        String str = String.valueOf(value).trim();
        LocalDate resolved = resolveNaturalDate(str);
        if (resolved != null) {
            return resolved.toString();
        }
        try {
            return LocalDate.parse(str).toString();
        } catch (DateTimeParseException exception) {
            throw new BadRequestException(entityMessage(entity, field, "must be an ISO date (yyyy-MM-dd)"));
        }
    }

    public static LocalDate resolveNaturalDate(String str) {
        if (str == null || str.isBlank()) return null;
        String lower = str.trim().toLowerCase(Locale.ROOT);
        String norm = removeDiacritics(lower);
        LocalDate today = LocalDate.now();

        if (norm.contains("hom nay") || norm.contains("ngay nay") || norm.contains("chieu nay")
                || norm.contains("sang nay") || norm.contains("toi nay") || norm.contains("today")) {
            return today;
        }
        if (norm.contains("ngay mai") || norm.contains("mai") || norm.contains("tomorrow")) {
            return today.plusDays(1);
        }
        if (norm.contains("ngay kia") || norm.contains("kia") || norm.contains("mot")) {
            return today.plusDays(2);
        }
        if (norm.contains("hom qua") || norm.contains("yesterday")) {
            return today.minusDays(1);
        }

        int currentDow = today.getDayOfWeek().getValue(); // Monday=1, ..., Sunday=7
        LocalDate mondayThisWeek = today.minusDays(currentDow - 1);

        if (norm.contains("cuoi tuan sau") || norm.contains("next weekend")) {
            return mondayThisWeek.plusDays(12); // Saturday next week
        }
        if (norm.contains("cuoi tuan nay") || norm.contains("cuoi tuan") || norm.contains("this weekend") || norm.contains("weekend")) {
            return mondayThisWeek.plusDays(5); // Saturday this week
        }

        String[] dayKeys = {
            "thu hai", "thu bay", "thu sau", "thu nam", "thu ba", "thu tu",
            "chu nhat", "wednesday", "thursday", "saturday", "tuesday", "monday", "friday", "sunday",
            "thu 2", "thu 3", "thu 4", "thu 5", "thu 6", "thu 7",
            "\\bmon\\b", "\\btue\\b", "\\bwed\\b", "\\bthu\\b", "\\bfri\\b", "\\bsat\\b", "\\bsun\\b", "\\bcn\\b"
        };
        int[] dayVals = {
            1, 6, 5, 4, 2, 3,
            7, 3, 4, 6, 2, 1, 5, 7,
            1, 2, 3, 4, 5, 6,
            1, 2, 3, 4, 5, 6, 7, 7
        };

        Integer targetDay = null;
        for (int i = 0; i < dayKeys.length; i++) {
            String k = dayKeys[i];
            if (k.startsWith("\\b")) {
                if (Pattern.compile(k).matcher(norm).find()) {
                    targetDay = dayVals[i];
                    break;
                }
            } else if (norm.contains(k)) {
                targetDay = dayVals[i];
                break;
            }
        }

        if (targetDay != null) {
            boolean isNextWeek = norm.contains("tuan sau") || norm.contains("tuan toi") || norm.contains("next");
            boolean isThisWeek = norm.contains("tuan nay") || norm.contains("this");

            if (isNextWeek) {
                LocalDate mondayNextWeek = mondayThisWeek.plusDays(7);
                return mondayNextWeek.plusDays(targetDay - 1);
            } else if (isThisWeek) {
                return mondayThisWeek.plusDays(targetDay - 1);
            } else {
                if (targetDay > currentDow) {
                    return mondayThisWeek.plusDays(targetDay - 1);
                } else {
                    LocalDate mondayNextWeek = mondayThisWeek.plusDays(7);
                    return mondayNextWeek.plusDays(targetDay - 1);
                }
            }
        }

        Matcher m = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?").matcher(str.trim());
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = m.group(3) != null
                        ? (m.group(3).length() == 2 ? 2000 + Integer.parseInt(m.group(3)) : Integer.parseInt(m.group(3)))
                        : today.getYear();
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
        }

        try {
            return LocalDate.parse(str.trim());
        } catch (Exception ignored) {}

        return null;
    }

    private static String removeDiacritics(String text) {
        if (text == null) return "";
        String nfd = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        String withoutMarks = nfd.replaceAll("\\p{M}", "");
        return withoutMarks.replace('đ', 'd').replace('Đ', 'D');
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
