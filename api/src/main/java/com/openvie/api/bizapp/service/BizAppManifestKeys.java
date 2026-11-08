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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract guard for the raw manifest JSON.
 *
 * <p>{@code Manifest} is deserialized with {@code ignoreUnknown}, so a key the
 * contract does not declare (an AI draft emitting top-level {@code reports},
 * {@code hooks} or {@code maxLength}) would be dropped silently while
 * validation still reported {@code ok: true} — the draft installs without the
 * report/hook/length the author asked for. This walker reports those keys with
 * the correction that usually fixes them, so the drafting repair loop and the
 * composer validation panel can show something actionable.
 */
public final class BizAppManifestKeys {

    private static final int MAX_MESSAGES = 12;

    private static final Set<String> MANIFEST = Set.of("schema", "kind", "name", "version",
            "icon", "storage", "rbac", "entities", "blocks", "ui", "conversational");
    private static final Set<String> ENTITY = Set.of("name", "label", "fields", "knowledge", "rbac", "hooks");
    private static final Set<String> FIELD = Set.of("name", "type", "entity", "required", "maxLen",
            "format", "options", "pii", "label", "optionLabels");
    private static final Set<String> BLOCK = Set.of("id", "type", "params");
    private static final Set<String> RBAC = Set.of("read", "write", "delete", "approve", "submit");
    private static final Set<String> UI = Set.of("views", "layout");
    private static final Set<String> CONVERSATIONAL = Set.of("triggers", "description");
    private static final Set<String> LOCALIZED = Set.of("vi", "en");
    private static final Set<String> KNOWLEDGE = Set.of("project", "titleField", "fields");
    private static final Set<String> OPTION_DETAIL = Set.of("label", "aliases");
    private static final Set<String> VIEW = Set.of("name", "entity", "title", "fields", "groupBy",
            "metrics", "filters", "orderBy", "limit", "engine");
    private static final Set<String> METRIC = Set.of("fn", "field", "as");
    private static final Set<String> FILTER = Set.of("field", "op", "value", "values", "param");
    private static final Set<String> ORDER = Set.of("by", "dir");
    private static final Set<String> POINT = Set.of("x", "y");

    private BizAppManifestKeys() {
    }

    /**
     * @return human-readable messages for every key the contract does not
     *         declare, capped so a malformed draft cannot flood the panel.
     */
    public static List<String> unknownKeys(Map<String, Object> raw) {
        List<String> messages = new ArrayList<>();
        if (raw == null) {
            return messages;
        }
        check(messages, raw, MANIFEST, "manifest");
        localized(messages, raw.get("name"), "name");
        rbac(messages, raw.get("rbac"), "rbac");
        entities(messages, raw.get("entities"));
        blocks(messages, raw.get("blocks"));
        ui(messages, raw.get("ui"));
        conversational(messages, raw.get("conversational"));
        return messages;
    }

    private static void entities(List<String> messages, Object rawEntities) {
        if (!(rawEntities instanceof List<?> entities)) {
            return;
        }
        for (int index = 0; index < entities.size(); index++) {
            Map<String, Object> entity = map(entities.get(index));
            if (entity == null) {
                continue;
            }
            String entityName = text(entity.get("name"));
            String path = "entities[" + (entityName == null ? index : entityName) + "]";
            check(messages, entity, ENTITY, path);
            localized(messages, entity.get("label"), path + ".label");
            rbac(messages, entity.get("rbac"), path + ".rbac");
            knowledge(messages, entity.get("knowledge"), path + ".knowledge");
            if (entity.get("fields") instanceof List<?> fields) {
                for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                    Map<String, Object> field = map(fields.get(fieldIndex));
                    if (field == null) {
                        continue;
                    }
                    String fieldName = text(field.get("name"));
                    String fieldPath = path + ".fields[" + (fieldName == null ? fieldIndex : fieldName) + "]";
                    check(messages, field, FIELD, fieldPath,
                            Map.of("maxLength", "the contract key is \"maxLen\""));
                    localized(messages, field.get("label"), fieldPath + ".label");
                    if (field.get("optionLabels") instanceof Map<?, ?> labels) {
                        for (Map.Entry<?, ?> entry : labels.entrySet()) {
                            Map<String, Object> detail = map(entry.getValue());
                            if (detail != null) {
                                check(messages, detail, OPTION_DETAIL,
                                        fieldPath + ".optionLabels." + entry.getKey());
                            }
                        }
                    }
                }
            }
        }
    }

    private static void blocks(List<String> messages, Object rawBlocks) {
        if (!(rawBlocks instanceof List<?> blocks)) {
            return;
        }
        for (int index = 0; index < blocks.size(); index++) {
            Map<String, Object> block = map(blocks.get(index));
            if (block == null) {
                continue;
            }
            String blockId = text(block.get("id"));
            String path = "blocks[" + (blockId == null ? index : blockId) + "]";
            check(messages, block, BLOCK, path);
            if (!"report".equals(text(block.get("type")))) {
                continue;
            }
            Map<String, Object> params = map(block.get("params"));
            if (params == null || !(params.get("views") instanceof List<?> views)) {
                continue;
            }
            for (int viewIndex = 0; viewIndex < views.size(); viewIndex++) {
                Map<String, Object> view = map(views.get(viewIndex));
                if (view == null) {
                    continue;
                }
                String viewName = text(view.get("name"));
                String viewPath = path + ".params.views[" + (viewName == null ? viewIndex : viewName) + "]";
                check(messages, view, VIEW, viewPath);
                elements(messages, view.get("metrics"), METRIC, viewPath + ".metrics");
                elements(messages, view.get("filters"), FILTER, viewPath + ".filters");
                elements(messages, view.get("orderBy"), ORDER, viewPath + ".orderBy");
            }
        }
    }

    private static void ui(List<String> messages, Object rawUi) {
        Map<String, Object> ui = map(rawUi);
        if (ui == null) {
            return;
        }
        check(messages, ui, UI, "ui");
        if (ui.get("layout") instanceof Map<?, ?> layout) {
            for (Map.Entry<?, ?> entry : layout.entrySet()) {
                Map<String, Object> point = map(entry.getValue());
                if (point != null) {
                    check(messages, point, POINT, "ui.layout." + entry.getKey());
                }
            }
        }
    }

    private static void conversational(List<String> messages, Object raw) {
        Map<String, Object> conversational = map(raw);
        if (conversational != null) {
            check(messages, conversational, CONVERSATIONAL, "conversational");
        }
    }

    private static void knowledge(List<String> messages, Object raw, String path) {
        Map<String, Object> knowledge = map(raw);
        if (knowledge != null) {
            check(messages, knowledge, KNOWLEDGE, path);
        }
    }

    private static void rbac(List<String> messages, Object raw, String path) {
        Map<String, Object> rbac = map(raw);
        if (rbac != null) {
            check(messages, rbac, RBAC, path);
        }
    }

    private static void localized(List<String> messages, Object raw, String path) {
        Map<String, Object> localized = map(raw);
        if (localized != null) {
            check(messages, localized, LOCALIZED, path);
        }
    }

    private static void elements(List<String> messages, Object raw, Set<String> allowed, String path) {
        if (!(raw instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            Map<String, Object> element = map(item);
            if (element != null) {
                check(messages, element, allowed, path + "[]");
            }
        }
    }

    private static void check(List<String> messages, Map<String, Object> node,
            Set<String> allowed, String path) {
        check(messages, node, allowed, path, Map.of());
    }

    private static void check(List<String> messages, Map<String, Object> node,
            Set<String> allowed, String path, Map<String, String> hints) {
        if (messages.size() >= MAX_MESSAGES) {
            return;
        }
        for (String key : new LinkedHashMap<>(node).keySet()) {
            if (allowed.contains(key)) {
                continue;
            }
            String detail = hints.get(key);
            if (detail == null) {
                detail = switch (key) {
                    case "reports" -> "report views belong in a \"report\" block: "
                            + "{\"type\":\"report\",\"params\":{\"views\":[...]}}";
                    case "hooks" -> "hooks belong on each entity: entities[].hooks {\"onCreate\":[...]}";
                    default -> "allowed keys: " + String.join(", ", allowed.stream().sorted().toList());
                };
            }
            hint(messages, path + "." + key, detail);
        }
    }

    private static void hint(List<String> messages, String path, String detail) {
        if (messages.size() >= MAX_MESSAGES) {
            return;
        }
        messages.add("unknown key " + path + " — " + detail);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        raw.forEach((key, item) -> typed.put(String.valueOf(key), item));
        return typed;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
