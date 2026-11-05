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

import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppSchemaSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntitySchemaSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldSummary;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;

import java.util.*;

/**
 * Deterministic schema digest for LLM context exposure (W7.1).
 */
public final class BizAppSchemaDigest {

    public static final int DEFAULT_MAX_APPS = 20;
    public static final int DEFAULT_MAX_ENTITIES_PER_APP = 10;
    public static final int DEFAULT_MAX_FIELDS_PER_ENTITY = 40;
    public static final int DEFAULT_MAX_LABEL_CHARS = 80;

    private BizAppSchemaDigest() {}

    public static AppSchemaSummary digest(Manifest manifest, String locale) {
        return digest(manifest, locale, DEFAULT_MAX_ENTITIES_PER_APP, DEFAULT_MAX_FIELDS_PER_ENTITY, DEFAULT_MAX_LABEL_CHARS);
    }

    public static AppSchemaSummary digest(
            Manifest manifest,
            String locale,
            int maxEntities,
            int maxFields,
            int maxLabelChars
    ) {
        if (manifest == null) return null;
        boolean isVi = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi");
        String appName = manifest.name() != null
                ? (isVi && manifest.name().vi() != null ? manifest.name().vi() : manifest.name().en())
                : manifest.kind();
        appName = trimLabel(appName, maxLabelChars);

        List<EntitySchemaSummary> entitySummaries = new ArrayList<>();
        if (manifest.entities() != null) {
            List<Entity> sortedEntities = new ArrayList<>(manifest.entities());
            sortedEntities.sort(Comparator.comparing(Entity::name));

            for (Entity entity : sortedEntities) {
                if (entitySummaries.size() >= maxEntities) break;
                String entityLabel = entity.label() != null
                        ? (isVi && entity.label().vi() != null ? entity.label().vi() : entity.label().en())
                        : entity.name();
                entityLabel = trimLabel(entityLabel, maxLabelChars);

                List<FieldSummary> fieldSummaries = new ArrayList<>();
                if (entity.fields() != null) {
                    List<Field> sortedFields = new ArrayList<>(entity.fields());
                    sortedFields.sort(Comparator.comparing(Field::name));

                    for (Field field : sortedFields) {
                        if (fieldSummaries.size() >= maxFields) break;
                        // Invariant I-14: PII fields are never exposed to LLM
                        if (Boolean.TRUE.equals(field.pii())) {
                            continue;
                        }
                        String fieldLabel = field.label() != null
                                ? (isVi && field.label().vi() != null ? field.label().vi() : field.label().en())
                                : field.name();
                        fieldLabel = trimLabel(fieldLabel, maxLabelChars);

                        boolean required = Boolean.TRUE.equals(field.required());
                        List<String> options = field.options() != null ? List.copyOf(field.options()) : List.of();
                        fieldSummaries.add(new FieldSummary(
                                field.name(),
                                field.type() != null ? field.type() : "string",
                                required,
                                options,
                                field.maxLen(),
                                field.format(),
                                fieldLabel
                        ));
                    }
                }
                entitySummaries.add(new EntitySchemaSummary(entity.name(), entityLabel, List.copyOf(fieldSummaries)));
            }
        }

        return new AppSchemaSummary(
                manifest.kind(),
                appName,
                manifest.version() != null ? manifest.version() : 1,
                List.copyOf(entitySummaries)
        );
    }

    private static String trimLabel(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars);
    }
}
