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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.BizAppProperties;
import com.openvie.api.bizapp.dto.BizAppDtos.DraftView;
import com.openvie.api.bizapp.dto.BizAppDtos.GenerateDraftRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.GenerateDraftResponse;
import com.openvie.api.bizapp.dto.BizAppDtos.SaveDraftRequest;
import com.openvie.api.bizapp.dto.BizAppDtos.ValidationResult;
import com.openvie.api.bizapp.model.BizAppDraft;
import com.openvie.api.bizapp.model.BizAppInstallation;
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
import com.openvie.api.bizapp.repository.BizAppInstallationRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service managing AI manifest drafting, stateless validation, and draft lifecycle (Workstream A).
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-DRAFT")
public class BizAppDraftService {

    private final BizAppDraftRepository draftRepository;
    private final BizAppInstallationRepository installationRepository;
    private final BizAppManifestValidator validator;
    private final BizAppManifestParser parser;
    private final AiInferenceApi aiInferenceApi;
    private final BizAppProperties properties;
    private final BizAppRuntime runtime;
    private final ObjectMapper objectMapper;

    /** The only BizApp-tier approval process bundled with the platform. */
    private static final String GENERIC_APPROVAL_BPMN = "processes/bizapp-single-approval.bpmn20.xml";

    private static final String APPROVAL_WORKITEM_BLOCK = "approval_workitem";

    public ValidationResult validateStateless(Map<String, Object> rawManifest) {
        if (rawManifest == null) {
            return new ValidationResult(false, List.of("Manifest cannot be null"));
        }
        try {
            var manifest = parser.parse(rawManifest);
            validator.validate(manifest, false);
            return new ValidationResult(true, List.of());
        } catch (BadRequestException ex) {
            return new ValidationResult(false, List.of(ex.getMessage()));
        } catch (Exception ex) {
            return new ValidationResult(false, List.of("Invalid manifest syntax: " + ex.getMessage()));
        }
    }

    @Transactional
    public GenerateDraftResponse generateDraft(UUID orgId, UUID tenantId, UUID actorId, GenerateDraftRequest request) {
        if (!properties.aiDraftingEnabled()) {
            throw new ResourceNotFoundException("AI manifest drafting is disabled on this installation");
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new BadRequestException("Prompt is required for drafting");
        }

        String systemPrompt = """
                You are OpenVie's AI BizApp Manifest Generator. Generate a valid bizapp.v1 JSON manifest matching the user request.
                Strict Rules:
                1. schema MUST be "bizapp.v1".
                2. kind: lowercase letters and hyphens (e.g. "inventory", "leads").
                3. name: localized object {"en": "...", "vi": "..."}.
                4. entities: array of entities with name, label (localized object {"en": "...", "vi": "..."}), and fields.
                5. Field types: "string", "number", "boolean", "enum", "date", "ref".
                6. storage: "jsonb" (never "typed").
                7. rbac: map of capabilities to role arrays (allowed capabilities: "read", "write", "delete", "approve", "submit"; roles: "ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"). "submit" defaults to "write" when omitted.
                8. NO SQL anywhere in the manifest! Reports must use declarative views with groupBy, metrics, filters, orderBy.
                9. Never include credentials or private keys in the manifest.
                10. blocks: optional array of blocks to automate workflows:
                    - approval_workitem: at most ONE per manifest: {"id": "approvalBlockId", "type": "approval_workitem", "params": {"bpmnTemplate": "processes/bizapp-single-approval.bpmn20.xml", "candidateRole": "WORKSPACE_ADMIN"|"ORG_OWNER"}}. When rbac.approve is declared, candidateRole MUST be one of its roles. An approval block hooked on "onCreate"/"onUpdate" submits the record automatically for that event.
                    - notify: {"id": "notifyBlockId", "type": "notify", "params": {"to": ["creator"|"record.<emailField>"|"config.<key>"|"someone@example.com"], "title": "...", "subject": "..."}}
                11. hooks: inside an entity, link lifecycle events to declared block IDs:
                    - "onCreate": ["blockId"] (runs when a record is created)
                    - "onUpdate": ["blockId"] (runs when updated, e.g. requesting approval on stage changes)
                    - "onDecide": ["blockId"] (runs after approval verdict, e.g. sending notification email; NEVER target approval_workitem here)
                """;

        String userPrompt = "Create an application manifest for: " + request.prompt();
        String currentOutput = "";
        ValidationResult validation = new ValidationResult(false, List.of("Draft unvalidated"));
        Map<String, Object> manifestMap = Map.of();

        // Up to 2 retries in repair loop
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var aiResp = aiInferenceApi.completeJson(new AiInferenceApi.StructuredJsonRequest(
                        userPrompt,
                        systemPrompt,
                        "bizapp.v1.schema.json",
                        "vylinh",
                        0.2f,
                        2048
                ));
                currentOutput = aiResp.jsonOutput();
                String json = extractJson(currentOutput);
                Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                manifestMap = normalizeGeneratedManifest(parsed, "app-draft");
                validation = validateStateless(manifestMap);
                if (validation.ok()) {
                    break;
                }
                userPrompt += "\n\nThe generated manifest had validation errors: " + String.join("; ", validation.errors()) + ". Please fix them.";
            } catch (Exception ex) {
                validation = new ValidationResult(false, List.of("JSON parse/generation error: " + ex.getMessage()));
            }
        }

        String title = "Bản thảo ứng dụng";
        if (manifestMap.containsKey("name") && manifestMap.get("name") instanceof Map<?, ?> mName) {
            if (mName.get("vi") != null && !String.valueOf(mName.get("vi")).isBlank()) {
                title = String.valueOf(mName.get("vi"));
            } else if (mName.get("en") != null) {
                title = String.valueOf(mName.get("en"));
            }
        }

        BizAppDraft draft = new BizAppDraft();
        draft.setOrgId(orgId);
        draft.setCreatedBy(actorId);
        draft.setSource("ai");
        draft.setTitle(title);
        draft.setManifest(manifestMap);
        draft.setValidation(Map.of("ok", validation.ok(), "errors", validation.errors()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generator", "ai");
        meta.put("model", "vylinh");
        meta.put("prompt", request.prompt());
        meta.put("title", title);
        draft.setMeta(meta);

        BizAppDraft saved = draftRepository.save(draft);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_DRAFT_GENERATED, "bizapp_draft",
                saved.getId(), Map.of("title", title, "valid", validation.ok()));

        return new GenerateDraftResponse(saved.getId(), saved.getManifest(), validation, saved.getMeta());
    }

    @Transactional
    public GenerateDraftResponse generateDraftFromChat(
            UUID orgId, UUID tenantId, UUID actorId, UUID sessionId, String hint, List<String> conversationTurns) {
        if (!properties.aiDraftingEnabled()) {
            throw new ResourceNotFoundException("AI manifest drafting is disabled on this installation");
        }
        StringBuilder sb = new StringBuilder();
        if (hint != null && !hint.isBlank()) {
            sb.append("Hint: ").append(hint).append("\n\n");
        }
        sb.append("Recent conversation turns:\n");
        for (String turn : conversationTurns) {
            sb.append(turn).append("\n");
        }
        GenerateDraftResponse resp = generateDraft(orgId, tenantId, actorId, new GenerateDraftRequest(sb.toString(), "vi", null));
        draftRepository.findByIdAndOrgId(resp.draftId(), orgId).ifPresent(d -> {
            Map<String, Object> meta = new HashMap<>(d.getMeta() != null ? d.getMeta() : Map.of());
            meta.put("sessionId", sessionId != null ? sessionId.toString() : null);
            meta.put("model", "vylinh");
            d.setMeta(meta);
            draftRepository.save(d);
        });
        return resp;
    }

    @Transactional(readOnly = true)
    public List<DraftView> listDrafts(UUID orgId) {
        return draftRepository.findByOrgIdOrderByUpdatedAtDesc(orgId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public DraftView getDraft(UUID orgId, UUID draftId) {
        return draftRepository.findByIdAndOrgId(draftId, orgId)
                .map(this::toView)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + draftId));
    }

    @Transactional
    public DraftView saveDraft(UUID orgId, UUID tenantId, UUID actorId, SaveDraftRequest request) {
        BizAppDraft draft = request.id() != null
                ? draftRepository.findByIdAndOrgId(request.id(), orgId).orElseGet(BizAppDraft::new)
                : new BizAppDraft();

        if (request.id() != null) {
            draft.setId(request.id());
        }
        draft.setOrgId(orgId);
        draft.setCreatedBy(actorId);
        draft.setSource(request.source() != null ? request.source() : "canvas");
        draft.setTitle(request.title() != null ? request.title() : "Untitled Draft");
        draft.setManifest(request.manifest());

        ValidationResult val = validateStateless(request.manifest());
        draft.setValidation(Map.of("ok", val.ok(), "errors", val.errors()));
        draft.setMeta(request.meta() != null ? request.meta() : Map.of("generator", "human"));

        BizAppDraft saved = draftRepository.save(draft);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_DRAFT_CREATED, "bizapp_draft",
                saved.getId(), Map.of("title", saved.getTitle()));
        syncInstalledDisplayName(orgId, actorId, saved);

        return toView(saved);
    }

    /**
     * Display-name propagation: drafts own the name users see. Saving a draft
     * whose kind is already installed keeps every active installation's
     * display name in step, while the installation's frozen schema, records and
     * workflow stay untouched.
     */
    private void syncInstalledDisplayName(UUID orgId, UUID actorId, BizAppDraft draft) {
        if (orgId == null || draft == null || draft.getManifest() == null) {
            return;
        }
        Map<String, Object> manifest = draft.getManifest();
        String kind = stringOrNull(manifest.get("kind"));
        if (kind == null || !(manifest.get("name") instanceof Map<?, ?> draftName)) {
            return;
        }
        String nextVi = stringOrNull(draftName.get("vi"));
        String nextEn = stringOrNull(draftName.get("en"));
        if (nextVi == null && nextEn == null) {
            return;
        }
        if (nextVi == null) nextVi = nextEn;
        if (nextEn == null) nextEn = nextVi;

        for (BizAppInstallation installation : installationRepository.findByOrgIdOrderByCreatedAtDesc(orgId)) {
            if (installation == null
                    || !installation.isActive()
                    || installation.getKind() == null
                    || !installation.getKind().equalsIgnoreCase(kind)) {
                continue;
            }
            Map<String, Object> installedManifest = installation.getManifest() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(installation.getManifest());
            String currentVi = installedManifest.get("name") instanceof Map<?, ?> installedName
                    ? stringOrNull(installedName.get("vi")) : null;
            String currentEn = installedManifest.get("name") instanceof Map<?, ?> installedName
                    ? stringOrNull(installedName.get("en")) : null;
            final String resolvedVi = nextVi;
            final String resolvedEn = nextEn;
            if (Objects.equals(currentVi, resolvedVi) && Objects.equals(currentEn, resolvedEn)) {
                continue;
            }
            Map<String, Object> installedName = new LinkedHashMap<>();
            if (installedManifest.get("name") instanceof Map<?, ?> current) {
                current.forEach((key, value) -> installedName.put(String.valueOf(key), value));
            }
            installedName.put("vi", resolvedVi);
            installedName.put("en", resolvedEn);
            installedManifest.put("name", installedName);
            installation.setManifest(installedManifest);
            installationRepository.save(installation);
            runtime.audit(installation.getTenantId(), actorId, LogAction.BIZAPP_APP_RENAMED,
                    "bizapp_installation", installation.getId(),
                    Map.of("app", kind, "nameVi", resolvedVi, "nameEn", resolvedEn));
        }
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @Transactional
    public void deleteDraft(UUID orgId, UUID tenantId, UUID actorId, UUID draftId) {
        BizAppDraft draft = draftRepository.findByIdAndOrgId(draftId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + draftId));
        draftRepository.delete(draft);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_DRAFT_DELETED, "bizapp_draft",
                draftId, Map.of("title", draft.getTitle()));
    }

    private static String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        String s = text.trim();
        if (s.contains("```json")) {
            int start = s.indexOf("```json") + 7;
            int end = s.lastIndexOf("```");
            if (end > start) s = s.substring(start, end).trim();
        } else if (s.contains("```")) {
            int start = s.indexOf("```") + 3;
            int end = s.lastIndexOf("```");
            if (end > start) s = s.substring(start, end).trim();
        }
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1).trim();
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeGeneratedManifest(Map<String, Object> raw, String fallbackKind) {
        if (raw == null) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>(raw);
        if (map.containsKey("manifest") && map.get("manifest") instanceof Map<?, ?> inner) {
            map = new LinkedHashMap<>((Map<String, Object>) inner);
        } else if (map.containsKey("app") && map.get("app") instanceof Map<?, ?> inner) {
            map = new LinkedHashMap<>((Map<String, Object>) inner);
        }

        if (!map.containsKey("schema") || !"bizapp.v1".equals(map.get("schema"))) {
            map.put("schema", "bizapp.v1");
        }
        if (!map.containsKey("storage")) {
            map.put("storage", "jsonb");
        }
        if (!map.containsKey("version")) {
            map.put("version", 1);
        }
        if (!map.containsKey("kind") || map.get("kind") == null || String.valueOf(map.get("kind")).isBlank()) {
            map.put("kind", fallbackKind != null && !fallbackKind.isBlank() ? fallbackKind : "custom-app");
        } else {
            String k = String.valueOf(map.get("kind")).trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
            if (k.length() < 2) k = "app-" + k;
            map.put("kind", k);
        }

        // Ensure name is localized
        Object nameObj = map.get("name");
        if (nameObj instanceof String s) {
            map.put("name", Map.of("vi", s, "en", s));
        }

        // Ensure entities have valid fields
        List<Map<String, Object>> normEntities = new ArrayList<>();
        Object entitiesObj = map.get("entities");
        if (entitiesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> em) {
                    Map<String, Object> entityMap = new LinkedHashMap<>((Map<String, Object>) em);
                    String eName = String.valueOf(entityMap.getOrDefault("name", "item")).trim().toLowerCase(java.util.Locale.ROOT)
                            .replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
                    if (eName.length() < 2) eName = "entity_" + eName;
                    entityMap.put("name", eName);

                    Object lObj = entityMap.get("label");
                    if (lObj instanceof String s) {
                        entityMap.put("label", Map.of("vi", s, "en", s));
                    }

                    // Normalize fields
                    Object fieldsObj = entityMap.get("fields");
                    if (fieldsObj instanceof List<?> fList) {
                        List<Map<String, Object>> normFields = new ArrayList<>();
                        for (Object fItem : fList) {
                            if (fItem instanceof Map<?, ?> fm) {
                                Map<String, Object> fieldMap = new LinkedHashMap<>((Map<String, Object>) fm);
                                String fName = String.valueOf(fieldMap.getOrDefault("name", "field")).trim();
                                fieldMap.put("name", fName);
                                String fType = String.valueOf(fieldMap.getOrDefault("type", "string")).toLowerCase(java.util.Locale.ROOT);
                                if (!Set.of("string", "number", "boolean", "enum", "date", "ref").contains(fType)) {
                                    fType = "string";
                                }
                                fieldMap.put("type", fType);

                                // If enum used "values" instead of "options"
                                if ("enum".equals(fType) && !fieldMap.containsKey("options") && fieldMap.containsKey("values")) {
                                    fieldMap.put("options", fieldMap.get("values"));
                                }
                                if ("enum".equals(fType) && (!fieldMap.containsKey("options") || !(fieldMap.get("options") instanceof List<?>) || ((List<?>) fieldMap.get("options")).isEmpty())) {
                                    fieldMap.put("options", List.of("default"));
                                }

                                // If ref used "reference" instead of "entity"
                                if ("ref".equals(fType) && !fieldMap.containsKey("entity") && fieldMap.containsKey("reference")) {
                                    fieldMap.put("entity", fieldMap.get("reference"));
                                }

                                Object fLabel = fieldMap.get("label");
                                if (fLabel instanceof String s) {
                                    fieldMap.put("label", Map.of("vi", s, "en", s));
                                }
                                normFields.add(fieldMap);
                            }
                        }
                        entityMap.put("fields", normFields);
                    }
                    normEntities.add(entityMap);
                }
            }
        }
        if (normEntities.isEmpty()) {
            normEntities.add(Map.of(
                    "name", "items",
                    "label", Map.of("vi", "Dữ liệu", "en", "Items"),
                    "fields", List.of(Map.of("name", "title", "type", "string", "required", true))
            ));
        }
        map.put("entities", normEntities);

        // Normalize RBAC: ensure valid capability keys
        Object rbacObj = map.get("rbac");
        boolean validRbac = false;
        if (rbacObj instanceof Map<?, ?> rbacMap) {
            validRbac = rbacMap.keySet().stream()
                    .anyMatch(k -> Set.of("read", "write", "delete", "approve", "submit").contains(String.valueOf(k).toLowerCase(java.util.Locale.ROOT)));
        }
        if (!validRbac) {
            map.put("rbac", Map.of(
                    "read", List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"),
                    "write", List.of("ORG_OWNER", "WORKSPACE_ADMIN"),
                    "delete", List.of("ORG_OWNER"),
                    "approve", List.of("ORG_OWNER", "WORKSPACE_ADMIN")
            ));
        }

        // Clamp workflow blocks onto the platform asset catalog. A manifest
        // binds to platform assets that actually exist: approval blocks may
        // only reference the bundled generic single-approval BPMN (its
        // candidateRole/SLA params parameterize it at submit time). Small
        // models invent bespoke process file paths that do not exist and
        // would deadlock the app at install time; rewriting here is the
        // deterministic backstop so the draft stays installable without a
        // repair round-trip ("skip, not fail" like the other normalizers).
        normalizeGeneratedBlocks(map);

        // Normalize UI views: ensure default registry and form views exist
        Object uiObj = map.get("ui");
        boolean validUi = false;
        if (uiObj instanceof Map<?, ?> uiMap && uiMap.get("views") instanceof List<?> vList && !vList.isEmpty()) {
            validUi = true;
        }
        if (!validUi) {
            List<String> views = new ArrayList<>();
            for (Map<String, Object> em : normEntities) {
                String eName = String.valueOf(em.get("name"));
                views.add("registry:" + eName);
                views.add("form:" + eName);
            }
            map.put("ui", Map.of("views", views));
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private void normalizeGeneratedBlocks(Map<String, Object> map) {
        if (!(map.get("blocks") instanceof List<?> blocks)) {
            return;
        }
        List<Map<String, Object>> normBlocks = new ArrayList<>();
        String keptApprovalId = null;
        Map<String, String> droppedApprovalIds = new LinkedHashMap<>();
        for (Object blockItem : blocks) {
            if (!(blockItem instanceof Map<?, ?> blockRaw)) {
                continue;
            }
            Map<String, Object> blockMap = new LinkedHashMap<>((Map<String, Object>) blockRaw);
            if (APPROVAL_WORKITEM_BLOCK.equals(String.valueOf(blockMap.get("type")))) {
                String blockId = String.valueOf(blockMap.get("id"));
                if (keptApprovalId != null) {
                    // The manifest supports exactly one approval block; a small
                    // model emitting several would fail validation at install.
                    // Keep the first and repoint its hooks so the draft stays
                    // installable ("skip, not fail").
                    droppedApprovalIds.put(blockId, keptApprovalId);
                    log.warn("Draft declares multiple approval_workitem blocks; dropping {} and keeping {}",
                            blockId, keptApprovalId);
                    continue;
                }
                keptApprovalId = blockId;
                Map<String, Object> params = blockMap.get("params") instanceof Map<?, ?> paramsRaw
                        ? new LinkedHashMap<>((Map<String, Object>) paramsRaw)
                        : new LinkedHashMap<>();
                String bpmn = String.valueOf(params.get("bpmnTemplate"));
                if (!GENERIC_APPROVAL_BPMN.equals(bpmn)) {
                    params.put("bpmnTemplate", GENERIC_APPROVAL_BPMN);
                }
                String candidateRole = String.valueOf(params.get("candidateRole"));
                if (!BizAppManifestValidator.APPROVABLE_ROLES.contains(candidateRole)) {
                    params.put("candidateRole", "WORKSPACE_ADMIN");
                }
                blockMap.put("params", params);
            }
            normBlocks.add(blockMap);
        }
        map.put("blocks", normBlocks);
        if (!droppedApprovalIds.isEmpty()) {
            rewriteDroppedApprovalHooks(map, droppedApprovalIds);
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteDroppedApprovalHooks(Map<String, Object> map, Map<String, String> droppedApprovalIds) {
        if (!(map.get("entities") instanceof List<?> entities)) {
            return;
        }
        List<Map<String, Object>> rewritten = new ArrayList<>();
        for (Object entityItem : entities) {
            if (!(entityItem instanceof Map<?, ?> entityRaw)) {
                continue;
            }
            Map<String, Object> entityMap = new LinkedHashMap<>((Map<String, Object>) entityRaw);
            if (entityRaw.get("hooks") instanceof Map<?, ?> hooksRaw) {
                Map<String, Object> hooks = new LinkedHashMap<>();
                hooksRaw.forEach((hookKey, hookValue) -> {
                    if (hookValue instanceof List<?> targets) {
                        List<String> remapped = new ArrayList<>();
                        for (Object target : targets) {
                            String value = String.valueOf(target);
                            remapped.add(droppedApprovalIds.getOrDefault(value, value));
                        }
                        hooks.put(String.valueOf(hookKey), remapped);
                    } else {
                        hooks.put(String.valueOf(hookKey), hookValue);
                    }
                });
                entityMap.put("hooks", hooks);
            }
            rewritten.add(entityMap);
        }
        map.put("entities", rewritten);
    }

    private DraftView toView(BizAppDraft d) {
        return new DraftView(
                d.getId(),
                d.getTitle(),
                d.getSource(),
                d.getManifest(),
                d.getValidation(),
                d.getMeta(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}
