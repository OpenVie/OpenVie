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

import com.openvie.api.bizapp.dto.BizAppManifest;
import com.openvie.api.bizapp.dto.BizAppManifest.Block;
import com.openvie.api.bizapp.dto.BizAppManifest.Entity;
import com.openvie.api.bizapp.dto.BizAppManifest.Field;
import com.openvie.api.bizapp.dto.BizAppManifest.Manifest;
import com.openvie.api.common.exception.custom.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The security and schema gate for BizApp manifests (frozen bizapp.v1 contract).
 *
 * <p>Enforces declarative report view schemas (no SQL), SSRF and secret controls,
 * strict limits, and fail-closed knowledge projection access rules.
 */
@Component
public class BizAppManifestValidator {

    public static final Set<String> BLOCK_TYPES = Set.of(
            "approval_workitem", "assistant", "knowledge_base", "report",
            "notify", "webhook_out", "external_call",
            "registry", "form", "timeline");

    public static final Set<String> FIELD_TYPES = Set.of("string", "number", "boolean", "enum", "date", "ref");

    /** Roles that may approve/author content in a workspace (validator authority). */
    public static final Set<String> APPROVABLE_ROLES = Set.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER");
    private static final Set<String> RBAC_KEYS = Set.of("read", "write", "delete", "approve");
    private static final Set<String> HOOK_KEYS = Set.of("onCreate", "onUpdate", "onDecide");
    private static final Set<String> VIEW_PREFIXES = Set.of("registry:", "form:", "assistant:", "report:", "timeline:");

    private static final Set<String> ALLOWED_METRIC_FNS = Set.of("count", "sum", "avg", "min", "max");
    private static final Set<String> ALLOWED_FILTER_OPS = Set.of(
            "eq", "ne", "in", "gt", "gte", "lt", "lte", "contains", "isnull");

    private static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern EVENT_SUFFIX = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,62}$");
    private static final Pattern BPMN_TEMPLATE = Pattern.compile("^processes/[a-z0-9][a-z0-9-]*\\.bpmn20\\.xml$");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\p{L}\\p{N} .,:;()@#%&_+\\-/']{1,200}$");

    public static final int MAX_ENTITIES = 20;
    public static final int MAX_FIELDS_PER_ENTITY = 40;
    public static final int MAX_BLOCKS = 40;
    public static final int MAX_VIEWS = 20;

    public void validate(Manifest manifest) {
        validate(manifest, false);
    }

    public void validate(Manifest manifest, boolean isPlatformBundled) {
        require(manifest != null, "manifest body required");
        require(BizAppManifest.SCHEMA.equals(manifest.schema()), "schema must be " + BizAppManifest.SCHEMA);
        require(manifest.kind() != null && KIND.matcher(manifest.kind()).matches(),
                "kind must match [a-z][a-z0-9-]{1,31}");
        require(manifest.name() != null && isPrintable(manifest.name().en())
                && isPrintable(manifest.name().vi()), "name.vi and name.en must be printable labels (1-200 chars)");
        int version = manifest.version() == null ? 1 : manifest.version();
        require(version >= 1, "version must be >= 1");

        if ("typed".equalsIgnoreCase(manifest.storage())) {
            require(isPlatformBundled, "storage: typed is restricted to platform-bundled templates only");
        }

        validateRbac(manifest.rbac());
        List<Entity> entities = requireList(manifest.entities(), "entities must not be empty");
        require(entities.size() <= MAX_ENTITIES, "manifest entities must be <= " + MAX_ENTITIES);

        Map<String, Entity> entityMap = new HashMap<>();
        for (Entity entity : entities) {
            validateEntity(entity, entityMap);
            entityMap.put(entity.name(), entity);
        }

        // Validate references across entities
        for (Entity entity : entities) {
            if (entity.fields() != null) {
                for (Field field : entity.fields()) {
                    if ("ref".equals(field.type())) {
                        require(field.entity() != null && entityMap.containsKey(field.entity()),
                                "ref field " + field.name() + " references undeclared entity " + field.entity());
                    }
                }
            }
            validateKnowledgeProjection(manifest, entity);
        }

        List<Block> blocks = manifest.blocks() == null ? List.of() : manifest.blocks();
        require(blocks.size() <= MAX_BLOCKS, "manifest blocks must be <= " + MAX_BLOCKS);
        for (Block block : blocks) {
            validateBlock(block, entityMap);
        }
        validateHooks(entities, blocks);
        validateUi(manifest.ui(), entities, blocks);
    }

    private void validateKnowledgeProjection(Manifest manifest, Entity entity) {
        if (entity.knowledge() == null || !Boolean.TRUE.equals(entity.knowledge().project())) {
            return;
        }
        // Fail-closed access rule (D): projection is allowed only when rbac.read covers every workspace role
        Map<String, List<String>> rbac = manifest.rbac();
        List<String> readRoles = rbac == null ? null : rbac.get("read");
        boolean coversAllRoles = readRoles != null
                && readRoles.contains("ORG_OWNER")
                && readRoles.contains("WORKSPACE_ADMIN")
                && readRoles.contains("MEMBER");
        require(coversAllRoles,
                "entity knowledge projection requires rbac.read to include all workspace roles [ORG_OWNER, WORKSPACE_ADMIN, MEMBER]");

        Map<String, Field> fields = new HashMap<>();
        if (entity.fields() != null) {
            entity.fields().forEach(f -> fields.put(f.name(), f));
        }

        if (entity.knowledge().titleField() != null) {
            require(fields.containsKey(entity.knowledge().titleField()),
                    "knowledge.titleField " + entity.knowledge().titleField() + " not found in entity fields");
        }

        if (entity.knowledge().fields() != null) {
            for (String fieldName : entity.knowledge().fields()) {
                require(fields.containsKey(fieldName),
                        "knowledge.fields contains undeclared field " + fieldName);
                Field f = fields.get(fieldName);
                require(!Boolean.TRUE.equals(f.pii()),
                        "pii field " + fieldName + " cannot be projected into knowledge base");
            }
        }
    }

    private void validateRbac(Map<String, List<String>> rbac) {
        if (rbac == null || rbac.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : rbac.entrySet()) {
            require(RBAC_KEYS.contains(entry.getKey()), "unknown rbac capability " + entry.getKey());
            requireList(entry.getValue(), "rbac." + entry.getKey() + " must not be empty")
                    .forEach(role -> require(APPROVABLE_ROLES.contains(role),
                            "unknown rbac role " + role));
        }
    }

    private void validateEntity(Entity entity, Map<String, Entity> seenEntities) {
        require(entity != null, "entity required");
        require(entity.name() != null && IDENT.matcher(entity.name()).matches(),
                "entity name must match [a-z][a-z0-9_]{0,63}");
        require(!seenEntities.containsKey(entity.name()), "duplicate entity name " + entity.name());
        if (entity.label() != null) {
            require(isPrintable(entity.label().en()) && isPrintable(entity.label().vi()),
                    "entity label.vi and label.en must be printable labels");
        }
        List<Field> fields = requireList(entity.fields(), "entity " + entity.name() + " needs fields");
        require(fields.size() <= MAX_FIELDS_PER_ENTITY,
                "entity " + entity.name() + " fields must be <= " + MAX_FIELDS_PER_ENTITY);
        Set<String> seen = new HashSet<>();
        for (Field field : fields) {
            require(field.name() != null && NAME.matcher(field.name()).matches(),
                    "field name must match [A-Za-z][A-Za-z0-9_]{0,63}");

            require(seen.add(field.name()), "duplicate field " + field.name());
            require(field.type() != null && FIELD_TYPES.contains(field.type()),
                    "field " + field.name() + " has unsupported type " + field.type());
            if ("enum".equals(field.type())) {
                require(field.options() != null && !field.options().isEmpty(),
                        "enum field " + field.name() + " requires options");
            }
            if (field.maxLen() != null) {
                require(field.maxLen() >= 1 && field.maxLen() <= 2000,
                        "field " + field.name() + " maxLen must be 1..2000");
            }
        }
    }

    private void validateBlock(Block block, Map<String, Entity> entityMap) {
        require(block != null && block.id() != null && NAME.matcher(block.id()).matches(),
                "block id must match [A-Za-z][A-Za-z0-9_]{0,63}");
        require(block.type() != null && BLOCK_TYPES.contains(block.type()),
                "unknown block type " + block.type());
        if ("approval_workitem".equals(block.type())) {
            String bpmn = text(block.params(), "bpmnTemplate");
            require(bpmn != null && BPMN_TEMPLATE.matcher(bpmn).matches(),
                    "approval_workitem.bpmnTemplate must match processes/<name>.bpmn20.xml (no traversal)");
            String role = text(block.params(), "candidateRole");
            require(role == null || APPROVABLE_ROLES.contains(role), "unsupported candidateRole " + role);
        }
        if ("report".equals(block.type())) {
            validateReportBlock(block, entityMap);
        }
        if ("external_call".equals(block.type())) {
            require(text(block.params(), "url") != null, "external_call requires url");
            require(block.params() != null
                            && block.params().get("credentialRef") instanceof String ref
                            && !ref.isBlank(),
                    "external_call secrets must use credentialRef, never inline values");
            requireNoInlineSecrets(block.params());
        }
        if ("notify".equals(block.type())) {
            require(paramsHasList(block.params(), "to") && !listOf(block.params().get("to")).isEmpty(),
                    "notify block requires a `to` list (creator or explicit email)");
        }
        if ("webhook_out".equals(block.type())) {
            require(paramsHasList(block.params(), "events") && !listOf(block.params().get("events")).isEmpty(),
                    "webhook_out block requires events");
            for (Object event : listOf(block.params().get("events"))) {
                require(event instanceof String kind && EVENT_SUFFIX.matcher(kind).matches(),
                        "invalid webhook event kind " + event);
            }
        }
    }

    private void validateReportBlock(Block block, Map<String, Entity> entityMap) {
        require(paramsHasList(block.params(), "views") && !listOf(block.params().get("views")).isEmpty(),
                "report block requires a non-empty views list");
        for (Object view : listOf(block.params().get("views"))) {
            require(view instanceof Map<?, ?>, "each report view must be an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> viewMap = (Map<String, Object>) view;

            // Reject raw SQL
            require(!viewMap.containsKey("sql"),
                    "free-form sql in report view is forbidden; use declarative views");

            String name = (String) viewMap.get("name");
            require(name != null && IDENT.matcher(name).matches(),
                    "report view name must match [a-z][a-z0-9_]{0,63}");

            String entityName = (String) viewMap.get("entity");
            require(entityName != null && entityMap.containsKey(entityName),
                    "report view " + name + " references unknown entity: " + entityName);

            Entity entity = entityMap.get(entityName);
            Map<String, Field> entityFields = new HashMap<>();
            if (entity.fields() != null) {
                entity.fields().forEach(f -> entityFields.put(f.name(), f));
            }

            Set<String> declaredAliases = new HashSet<>();

            // Group By
            if (viewMap.get("groupBy") instanceof List<?> groupBy) {
                for (Object g : groupBy) {
                    require(g instanceof String col && NAME.matcher(col).matches(),
                            "groupBy field must match [A-Za-z][A-Za-z0-9_]{0,63}");
                    require(entityFields.containsKey(g),
                            "report view " + name + " groupBy field " + g + " not found on entity " + entityName);
                    declaredAliases.add((String) g);
                }
            }

            // Metrics
            if (viewMap.get("metrics") instanceof List<?> metrics) {
                for (Object mObj : metrics) {
                    require(mObj instanceof Map<?, ?>, "each metric must be an object");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) mObj;
                    String fn = m.get("fn") == null ? null : String.valueOf(m.get("fn")).toLowerCase(Locale.ROOT);
                    require(fn != null && ALLOWED_METRIC_FNS.contains(fn), "unsupported metric function: " + fn);
                    String as = m.get("as") == null ? null : String.valueOf(m.get("as"));
                    require(as != null && IDENT.matcher(as).matches(),
                            "metric 'as' alias must match [a-z][a-z0-9_]{0,63}");
                    declaredAliases.add(as);

                    if (m.containsKey("field") && m.get("field") != null) {
                        String fieldName = String.valueOf(m.get("field"));
                        require(NAME.matcher(fieldName).matches(), "metric field must match identifier format");
                        require(entityFields.containsKey(fieldName),
                                "metric field " + fieldName + " not found on entity " + entityName);
                        Field f = entityFields.get(fieldName);
                        if ("sum".equals(fn) || "avg".equals(fn)) {
                            require("number".equals(f.type()),
                                    "metric " + fn + " requires numeric field, got " + f.type());
                        }
                    } else {
                        require("count".equals(fn), "metric function " + fn + " requires a field");
                    }
                }
            }

            // Filters
            if (viewMap.get("filters") instanceof List<?> filters) {
                for (Object fObj : filters) {
                    require(fObj instanceof Map<?, ?>, "each filter must be an object");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> f = (Map<String, Object>) fObj;
                    String fieldName = f.get("field") == null ? null : String.valueOf(f.get("field"));
                    require(fieldName != null && NAME.matcher(fieldName).matches(),
                            "filter field must match [A-Za-z][A-Za-z0-9_]{0,63}");
                    require(entityFields.containsKey(fieldName),
                            "filter field " + fieldName + " not found on entity " + entityName);
                    String op = f.get("op") == null ? null : String.valueOf(f.get("op")).toLowerCase(Locale.ROOT);
                    require(op != null && ALLOWED_FILTER_OPS.contains(op), "unsupported filter op: " + op);
                }
            }

            // Order By
            if (viewMap.get("orderBy") instanceof List<?> orderBy) {
                for (Object oObj : orderBy) {
                    require(oObj instanceof Map<?, ?>, "each orderBy must be an object");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> o = (Map<String, Object>) oObj;
                    String by = o.get("by") == null ? null : String.valueOf(o.get("by"));
                    require(by != null && NAME.matcher(by).matches(), "orderBy 'by' must match identifier format");
                    require(declaredAliases.contains(by) || entityFields.containsKey(by),
                            "orderBy target " + by + " is neither a declared field nor metric alias");
                    if (o.containsKey("dir")) {
                        String dir = String.valueOf(o.get("dir")).toLowerCase(Locale.ROOT);
                        require("asc".equals(dir) || "desc".equals(dir), "orderBy dir must be asc or desc");
                    }
                }
            }

            // Limit
            if (viewMap.get("limit") instanceof Number limitNum) {
                require(limitNum.intValue() >= 1 && limitNum.intValue() <= 500,
                        "report view limit must be 1..500");
            }

            // Engine
            if (viewMap.get("engine") instanceof String engine) {
                require("postgres".equalsIgnoreCase(engine) || "lakehouse".equalsIgnoreCase(engine),
                        "report view engine must be postgres or lakehouse");
            }
        }
    }

    private void validateHooks(List<Entity> entities, List<Block> blocks) {
        Set<String> blockIds = new HashSet<>();
        Set<String> webhookEvents = new HashSet<>();
        for (Block block : blocks) {
            blockIds.add(block.id());
            if ("webhook_out".equals(block.type()) && paramsHasList(block.params(), "events")) {
                listOf(block.params().get("events")).forEach(event -> webhookEvents.add((String) event));
            }
        }
        for (Entity entity : entities) {
            if (entity.hooks() == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> hook : entity.hooks().entrySet()) {
                require(HOOK_KEYS.contains(hook.getKey()), "unknown hook " + hook.getKey());
                requireList(hook.getValue(), hook.getKey() + " must not be empty")
                        .forEach(target -> {
                            if (target != null && target.startsWith("webhook_out:")) {
                                String event = target.substring("webhook_out:".length());
                                require(webhookEvents.contains(event),
                                        "hook target " + target + " has no webhook_out block declaring it");
                            } else {
                                require(blockIds.contains(target),
                                        "hook target block missing: " + target);
                            }
                        });
            }
        }
    }

    private void validateUi(BizAppManifest.Ui ui, List<Entity> entities, List<Block> blocks) {
        if (ui == null || ui.views() == null) {
            return;
        }
        require(ui.views().size() <= MAX_VIEWS, "manifest ui.views must be <= " + MAX_VIEWS);
        Set<String> entityNames = new HashSet<>();
        for (Entity entity : entities) {
            entityNames.add(entity.name());
        }
        Set<String> blockIds = new HashSet<>();
        Set<String> reportViews = new HashSet<>();
        for (Block block : blocks) {
            blockIds.add(block.id());
            if ("report".equals(block.type()) && paramsHasList(block.params(), "views")) {
                for (Object view : listOf(block.params().get("views"))) {
                    if (view instanceof Map<?, ?> map && map.get("name") instanceof String name) {
                        reportViews.add(name);
                    }
                }
            }
        }
        for (String view : ui.views()) {
            require(VIEW_PREFIXES.stream().anyMatch(view::startsWith), "unknown ui view " + view);
            String[] parts = view.split(":", 2);
            require(parts.length == 2 && !parts[1].isBlank(), "ui view " + view + " needs a target");
            String prefix = parts[0];
            String target = parts[1];
            switch (prefix) {
                case "assistant" -> require(blockIds.contains(target),
                        "ui view " + view + " references a missing assistant block");
                case "report" -> require(reportViews.contains(target),
                        "ui view " + view + " references a missing report view");
                case "registry", "form", "timeline" -> require(entityNames.contains(target),
                        "ui view " + view + " references a missing entity");
                default -> throw new BadRequestException("unknown ui view " + view);
            }
        }
    }

    private void requireNoInlineSecrets(Map<String, Object> params) {
        if (params == null) {
            return;
        }
        for (String key : params.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean secretShaped = lower.endsWith("password") || lower.endsWith("secret")
                    || lower.endsWith("apikey") || lower.endsWith("api_key")
                    || lower.equals("token") || lower.endsWith("token");
            require(!secretShaped, "inline field " + key + " looks like a secret; use credentialRef");
        }
    }

    private String text(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private boolean isPrintable(String value) {
        return value != null && PRINTABLE.matcher(value).matches();
    }

    private boolean paramsHasList(Map<String, Object> params, String key) {
        return params != null && params.get(key) instanceof List<?>;
    }

    private List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private <T> List<T> requireList(List<T> list, String message) {
        require(list != null && !list.isEmpty(), message);
        return list;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new BadRequestException(message);
        }
    }
}
