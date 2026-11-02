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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The security and schema gate for BizApp manifests.
 *
 * <p>Every rule here is enforced server-side on install, upgrade, and
 * custom-template save: unknown block types fail, report SQL is parsed as
 * SELECT-only with named {@code :tenant} / {@code :app} binds only, and
 * secrets may appear exclusively as a {@code credentialRef} resolved against
 * the organization's vault. A manifest that passes this validator is data;
 * one that does not never reaches storage.
 */
@Component
public class BizAppManifestValidator {

    public static final Set<String> BLOCK_TYPES = Set.of(
            "approval_workitem", "assistant", "knowledge_base", "report",
            "notify", "webhook_out", "external_call",
            "registry", "form", "timeline");

    public static final Set<String> FIELD_TYPES = Set.of("string", "number", "boolean", "enum", "date");

    private static final Set<String> APPROVABLE_ROLES = Set.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER");
    private static final Set<String> RBAC_KEYS = Set.of("read", "write", "delete", "approve");
    private static final Set<String> HOOK_KEYS = Set.of("onCreate", "onUpdate", "onDecide");
    private static final Set<String> VIEW_PREFIXES = Set.of("registry:", "form:", "assistant:", "report:", "timeline:");

    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern EVENT_SUFFIX = Pattern.compile("[a-z0-9][a-z0-9._-]{1,62}");
    private static final Pattern BPMN_TEMPLATE = Pattern.compile("processes/[a-z0-9][a-z0-9-]*\\.bpmn20\\.xml");
    private static final Pattern NAMED_PARAM = Pattern.compile(":[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern PRINTABLE = Pattern.compile("[\\p{L}\\p{N} .,:;()@#%&_+\\-/']{1,200}");

    /** SQL keywords that would make a "view" executable; compared whole-token. */
    private static final Set<String> FORBIDDEN_SQL_TOKENS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "PRAGMA", "CALL", "COPY", "MERGE", "VACUUM",
            "ANALYZE", "EXPLAIN", "ATTACH", "DETACH", "SET", "USE", "UNION");

    public void validate(Manifest manifest) {
        require(manifest != null, "manifest body required");
        require(BizAppManifest.SCHEMA.equals(manifest.schema()), "schema must be " + BizAppManifest.SCHEMA);
        require(manifest.kind() != null && KIND.matcher(manifest.kind()).matches(),
                "kind must match [a-z][a-z0-9-]{1,31}");
        require(manifest.name() != null && isPrintable(manifest.name().en())
                && isPrintable(manifest.name().vi()), "name.vi and name.en must be printable labels (1-200 chars)");
        int version = manifest.version() == null ? 1 : manifest.version();
        require(version >= 1, "version must be >= 1");

        validateRbac(manifest.rbac());
        List<Entity> entities = requireList(manifest.entities(), "entities must not be empty");
        for (Entity entity : entities) {
            validateEntity(entity);
        }
        List<Block> blocks = manifest.blocks() == null ? List.of() : manifest.blocks();
        for (Block block : blocks) {
            validateBlock(block);
        }
        validateHooks(entities, blocks);
        validateUi(manifest.ui(), entities, blocks);
    }

    /** RBAC map: capability → roles allowed. Missing key = admins implicitly allowed. */
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

    private void validateEntity(Entity entity) {
        require(entity != null, "entity required");
        require(entity.name() != null && IDENT.matcher(entity.name()).matches(),
                "entity name must match [a-z][a-z0-9_]{0,63}");
        if (entity.label() != null) {
            require(isPrintable(entity.label().en()) && isPrintable(entity.label().vi()),
                    "entity label.vi and label.en must be printable labels");
        }
        List<Field> fields = requireList(entity.fields(), "entity " + entity.name() + " needs fields");
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
                require(field.maxLen() >= 1 && field.maxLen() <= 1024,
                        "field " + field.name() + " maxLen must be 1..1024");
            }
        }
    }

    private void validateBlock(Block block) {
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
            require(paramsHasList(block.params(), "views") && !listOf(block.params().get("views")).isEmpty(),
                    "report block requires a non-empty views list");
            for (Object view : listOf(block.params().get("views"))) {
                require(view instanceof Map<?, ?>, "each report view must be an object");
                Map<?, ?> viewMap = (Map<?, ?>) view;
                require(viewMap.get("name") instanceof String name && NAME.matcher(name).matches()
                                && viewMap.get("sql") instanceof String sqlText && !sqlText.isBlank(),
                        "each report view needs name + sql");
                require(isSelectOnly(String.valueOf(viewMap.get("sql"))),
                        "report view sql must be one read-only SELECT with :tenant/:app binds only");
            }
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

    /**
     * Hook targets are either a block id (notify / external_call execution) or
     * {@code webhook_out:<event>} matching an event list of one webhook_out
     * block; the runtime prefixes the full {@code bizapp.<kind>.<event>} kind.
     */
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

    /** True when the whole string is exactly one SELECT (report views). */
    boolean isSelectOnly(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT ") || upper.contains(";")) {
            return false;
        }
        for (String word : upper.split("[\\s(),]+")) {
            if (FORBIDDEN_SQL_TOKENS.contains(word)) {
                return false;
            }
        }
        if (normalized.contains("${")) {
            return false;
        }
        var matcher = NAMED_PARAM.matcher(normalized);
        while (matcher.find()) {
            String name = matcher.group().substring(1);
            if (!"tenant".equals(name) && !"app".equals(name)) {
                return false;
            }
        }
        return true;
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
