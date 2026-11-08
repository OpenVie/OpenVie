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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Value("${app.bizapps.ai.draft-model:}")
    private String draftModel = "";

    @Value("${app.bizapps.ai.draft-fallback-model:}")
    private String draftFallbackModel = "";

    /**
     * Forward the real manifest JSON Schema to the model (Ollama structured
     * output) instead of a file name. The AI service treats a non-JSON string
     * as a legacy reference and drops the constraint, so passing the body is
     * what actually prevents keys like top-level {@code reports} or
     * {@code maxLength}. Set to false to fall back to prompt-only generation.
     */
    @Value("${app.bizapps.ai.draft-json-schema:true}")
    private boolean draftJsonSchema = true;

    private static final String MANIFEST_SCHEMA_RESOURCE = "contracts/bizapp/bizapp.v1.schema.json";
    private static final String MANIFEST_SCHEMA_NAME = "bizapp.v1.schema.json";

    private volatile String manifestSchemaCache;

    /** The only BizApp-tier approval process bundled with the platform. */
    private static final String GENERIC_APPROVAL_BPMN = "processes/bizapp-single-approval.bpmn20.xml";

    private static final String APPROVAL_WORKITEM_BLOCK = "approval_workitem";

    private static final Pattern EMPLOYEE_ACTOR = Pattern.compile(
            "\\b(?:nhan vien|nhan su|nguoi dung|moi nguoi|member|members|employee|employees|staff)\\b");

    private static final Pattern MEMBER_WRITE_ACTION = Pattern.compile(
            "\\b(?:dien|tao|lap|nhap|viet|gui|trinh|dang ky|yeu cau|submit|create|fill|request|send)\\b");

    private static final Pattern ADMIN_ONLY_CREATE = Pattern.compile(
            "\\bchi\\s+(?:quan ly|admin|quan tri vien|owner)s?\\s+(?:duoc\\s+)?"
                    + "(?:tao|dien|gui|nhap|viet|submit|create|fill)\\b");

    private static final Pattern MEMBER_DENIED = Pattern.compile(
            "\\b(?:khong|not)\\s+(?:duoc\\s+)?(?:tao|dien|gui|nhap|viet|submit|create|fill)\\b");

    private static final Pattern POLL_INTERVAL = Pattern.compile(
            "(?iu)(?:mỗi|cứ|every)\\s+(\\d+)\\s*(phút|giờ|ngày|minute|minutes|hour|hours|day|days)"
                    + "|(\\d+)\\s*(phút|giờ|ngày)\\s*(?:một lần|1 lần)");

    /** The user explicitly wants a notification when the record is created. */
    private static final Pattern IMMEDIATE_CREATE_NOTIFY = Pattern.compile(
            "\\b(?:khi|sau khi|ngay khi|vua|moi)\\s+(?:tao|tao moi|lap|nhap|dien|gui)\\b"
                    + "|\\b(?:gui|send)\\s+(?:email|mail|thong bao)\\s+(?:ngay|immediately)\\b"
                    + "|\\b(?:email|mail|thong bao)\\b[^\\n]{0,60}\\b(?:khi tao|ngay khi tao|on create)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public ValidationResult validateStateless(Map<String, Object> rawManifest) {
        if (rawManifest == null) {
            return new ValidationResult(false, List.of("Manifest cannot be null"));
        }
        // Unknown keys are dropped by Jackson, so surface them before the typed
        // validator sees a manifest that no longer contains the author's intent.
        List<String> unknownKeys = BizAppManifestKeys.unknownKeys(rawManifest);
        if (!unknownKeys.isEmpty()) {
            return new ValidationResult(false, unknownKeys);
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
                7. rbac: map of capabilities to role arrays (allowed capabilities: "read", "write", "delete", "approve", "submit"; roles: "ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER"). "submit" defaults to "write" when omitted. Declare rbac ONLY at the top level; entities must not contain an "rbac" object. If the request says employees/members fill or create records, include "MEMBER" in rbac.write (and rbac.submit when approval is requested).
                8. NO SQL anywhere in the manifest! Reports must use declarative views with groupBy, metrics, filters, orderBy.
                9. Never include credentials or private keys in the manifest.
                10. blocks: optional array of blocks to automate workflows:
                    - approval_workitem: at most ONE per manifest: {"id": "approvalBlockId", "type": "approval_workitem", "params": {"bpmnTemplate": "processes/bizapp-single-approval.bpmn20.xml", "candidateRole": "WORKSPACE_ADMIN"|"ORG_OWNER"}}. When rbac.approve is declared, candidateRole MUST be one of its roles. An approval block hooked on "onCreate"/"onUpdate" submits the record automatically for that event.
                    - notify: {"id": "notifyBlockId", "type": "notify", "params": {"to": ["creator"|"record.<emailField>"|"config.<key>"|"someone@example.com"], "title": "...", "subject": "..."}}
                    - external_call: only when the user explicitly asks to call an external API/device. HTTP: {"id": "...", "type": "external_call", "params": {"url": "https://host/path", "method": "POST", "credentialRef": "<vault entry name>", "bodyTemplate": "{\\"device\\":\\"{{record.name}}\\"}", "responseMap": {"<runtimeKey>": "/<jsonPointer>"}, "sink": {"type": "field", "mapping": {"<declared field>": "/<jsonPointer>"}}}}. MQTT: use "protocol": "mqtt", "brokerUrl": "ssl://host:8883", "topic": "...", "qos": 0|1, "maxMessages": 1..100 instead of url/method. A sink may be {"type": "record", "entity": "<declared entity>", "mapping": {...}, "dedupeKey": ["<mapped field>", ...]} instead of a field sink. Optional "schedule": {"every": "PT5M", "entity": "<declared entity>", "select": {"enabled": true}, "limit": 10}. Never inline secrets: credentialRef is a vault entry name the operator creates. Sink/responseMap values are JSON pointers starting with "/". Only map fields/entities that are declared in the manifest.
                11. hooks: inside an entity, link lifecycle events to declared block IDs:
                    - "onCreate": ["blockId"] (runs when a record is created)
                    - "onUpdate": ["blockId"] (runs when updated, e.g. requesting approval on stage changes)
                    - "onDecide": ["blockId"] (runs after approval verdict, e.g. sending notification email; NEVER target approval_workitem here)
                12. conversational: {"triggers": ["..."], "description": "..."} with 3-8 natural Vietnamese and/or English phrases a user would say to operate this app (e.g. "tạo phiếu xin mua", "đề nghị mua sắm"). Avoid generic phrases such as "quản lý" alone.
                """;

        String userPrompt = "Create an application manifest for: " + request.prompt();
        ModelAttempt chosen = generateWithRepair(userPrompt, systemPrompt, draftModel(), 3);
        boolean fallbackUsed = false;
        if (!chosen.validation().ok()) {
            String fallback = draftFallbackModel();
            if (fallback != null && !fallback.equals(chosen.model())) {
                String fallbackPrompt = userPrompt
                        + "\n\nThe generated manifest had validation errors: "
                        + String.join("; ", chosen.validation().errors()) + ". Please fix them.";
                ModelAttempt alternative = generateWithRepair(fallbackPrompt, systemPrompt, fallback, 1);
                fallbackUsed = true;
                if (alternative.validation().ok()) {
                    chosen = alternative;
                }
            }
        }
        Map<String, Object> manifestMap = chosen.manifest();
        ValidationResult validation = chosen.validation();

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
        meta.put("model", chosen.model() != null ? chosen.model() : "default");
        meta.put("attempts", chosen.attempts());
        meta.put("fallbackUsed", fallbackUsed);
        meta.put("prompt", request.prompt());
        meta.put("title", title);
        draft.setMeta(meta);

        BizAppDraft saved = draftRepository.save(draft);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_DRAFT_GENERATED, "bizapp_draft",
                saved.getId(), Map.of("title", title, "valid", validation.ok(),
                        "model", chosen.model() != null ? chosen.model() : "default",
                        "fallbackUsed", fallbackUsed));

        return new GenerateDraftResponse(saved.getId(), saved.getManifest(), validation, saved.getMeta());
    }

    private record ModelAttempt(Map<String, Object> manifest, ValidationResult validation,
            String model, int attempts) {
    }

    /** One model, up to {@code maxAttempts} with the existing validation repair loop. */
    private ModelAttempt generateWithRepair(String userPrompt, String systemPrompt,
            String model, int maxAttempts) {
        String prompt = userPrompt;
        Map<String, Object> manifestMap = Map.of();
        ValidationResult validation = new ValidationResult(false, List.of("Draft unvalidated"));
        int attempts = 0;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            attempts++;
            try {
                var aiResp = aiInferenceApi.completeJson(new AiInferenceApi.StructuredJsonRequest(
                        prompt,
                        systemPrompt,
                        manifestSchema(),
                        model,
                        0.2f,
                        2048
                ));
                String json = extractJson(aiResp.jsonOutput());
                Map<String, Object> parsed = objectMapper.readValue(
                        json, new TypeReference<Map<String, Object>>() {});
                manifestMap = normalizeGeneratedManifest(parsed, "app-draft", prompt);
                validation = validateStateless(manifestMap);
                if (validation.ok()) {
                    break;
                }
                prompt += "\n\nThe generated manifest had validation errors: "
                        + String.join("; ", validation.errors()) + ". Please fix them.";
            } catch (Exception ex) {
                validation = new ValidationResult(false, List.of("JSON parse/generation error: " + ex.getMessage()));
            }
        }
        return new ModelAttempt(manifestMap, validation, model, attempts);
    }

    private String draftModel() {
        return draftModel == null || draftModel.isBlank() ? null : draftModel.trim();
    }

    private String draftFallbackModel() {
        return draftFallbackModel == null || draftFallbackModel.isBlank()
                ? null : draftFallbackModel.trim();
    }

    /** Schema body for structured output, or the legacy file name as fallback. */
    String manifestSchema() {
        if (!draftJsonSchema) {
            return MANIFEST_SCHEMA_NAME;
        }
        String cached = manifestSchemaCache;
        if (cached == null) {
            cached = loadManifestSchema();
            manifestSchemaCache = cached;
        }
        return cached;
    }

    private String loadManifestSchema() {
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream(MANIFEST_SCHEMA_RESOURCE)) {
            if (in == null) {
                log.warn("Manifest schema {} is missing from the classpath; "
                        + "drafting keeps prompt-only generation", MANIFEST_SCHEMA_RESOURCE);
                return MANIFEST_SCHEMA_NAME;
            }
            String schema = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return schema.strip().startsWith("{") ? schema : MANIFEST_SCHEMA_NAME;
        } catch (Exception ex) {
            log.warn("Could not read the manifest schema: {}", ex.getMessage());
            return MANIFEST_SCHEMA_NAME;
        }
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
        return normalizeGeneratedManifest(raw, fallbackKind, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeGeneratedManifest(Map<String, Object> raw, String fallbackKind,
            String userPrompt) {
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
        normalizeNotifyRecipients(map);
        normalizeMemberCapabilities(map, userPrompt);
        normalizeGeneratedUi(map);
        normalizeWorkflowTriggers(map, userPrompt);

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
        Map<String, String> uniqueTypeIds = uniqueBlockIdsByType(normBlocks);
        if (!droppedApprovalIds.isEmpty() || !uniqueTypeIds.isEmpty()) {
            rewriteHooks(map, droppedApprovalIds, uniqueTypeIds);
        }
        ensureApprovalHook(map, keptApprovalId);
    }

    /**
     * An approval block with no lifecycle hook can never run: the app installs
     * but member-created records are never submitted. Small drafting models
     * regularly emit the block and forget the hook, so attach it to the first
     * entity's onCreate (the reviewed draft can still move it in the composer).
     */
    @SuppressWarnings("unchecked")
    private void ensureApprovalHook(Map<String, Object> map, String approvalId) {
        if (approvalId == null || !(map.get("entities") instanceof List<?> entities) || entities.isEmpty()) {
            return;
        }
        for (Object entityItem : entities) {
            if (!(entityItem instanceof Map<?, ?> entityMap)
                    || !(entityMap.get("hooks") instanceof Map<?, ?> hooks)) {
                continue;
            }
            for (Object targets : hooks.values()) {
                if (targets instanceof List<?> list && list.stream()
                        .anyMatch(target -> approvalId.equals(String.valueOf(target)))) {
                    return;
                }
            }
        }
        if (!(entities.get(0) instanceof Map<?, ?> firstRaw)) {
            return;
        }
        Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) firstRaw);
        Map<String, Object> hooks = entity.get("hooks") instanceof Map<?, ?> current
                ? new LinkedHashMap<>((Map<String, Object>) current)
                : new LinkedHashMap<>();
        List<Object> onCreate = hooks.get("onCreate") instanceof List<?> current
                ? new ArrayList<>(current)
                : new ArrayList<>();
        onCreate.add(approvalId);
        hooks.put("onCreate", onCreate);
        entity.put("hooks", hooks);
        List<Object> rewritten = new ArrayList<>(entities);
        rewritten.set(0, entity);
        map.put("entities", rewritten);
        log.info("AI draft: approval block {} had no hook; attached it to the first entity's onCreate",
                approvalId);
    }

    /**
     * Deterministic counterpart to the prompt guidance: when the request
     * explicitly says employees/members fill or create records, the generated
     * app must let MEMBER read and write, and submit when it has an approval.
     * This closes the gap where a small model writes an admin-only app-level
     * RBAC while the prose (or an entity-level list) clearly describes a
     * member workflow. Admin-only/denied clauses suppress the grant.
     */
    @SuppressWarnings("unchecked")
    private void normalizeMemberCapabilities(Map<String, Object> map, String userPrompt) {
        if (!promptRequestsMemberWorkflow(userPrompt)) {
            return;
        }
        Map<String, Object> rbac = map.get("rbac") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
        if (rbac.get("read") instanceof List<?>) {
            addRole(rbac, "read", "MEMBER");
        }
        if (rbac.get("write") instanceof List<?>) {
            addRole(rbac, "write", "MEMBER");
        } else {
            // An absent write list means "admins only"; keep both admins and
            // add the member grant instead of replacing them with MEMBER.
            rbac.put("write", new ArrayList<>(List.of("ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER")));
        }
        if (hasApprovalBlock(map) && rbac.get("submit") instanceof List<?>) {
            addRole(rbac, "submit", "MEMBER");
        }
        map.put("rbac", rbac);
    }

    private boolean promptRequestsMemberWorkflow(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return false;
        }
        String normalized = foldPrompt(userPrompt);
        for (String clause : normalized.split("[,.;!?\\n]+")) {
            if (!EMPLOYEE_ACTOR.matcher(clause).find()) {
                continue;
            }
            if (ADMIN_ONLY_CREATE.matcher(clause).find() || MEMBER_DENIED.matcher(clause).find()) {
                continue;
            }
            if (MEMBER_WRITE_ACTION.matcher(clause).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean promptRequestsCreationNotify(String userPrompt) {
        return userPrompt != null && !userPrompt.isBlank()
                && IMMEDIATE_CREATE_NOTIFY.matcher(foldPrompt(userPrompt)).find();
    }

    private static String foldPrompt(String text) {
        return java.text.Normalizer.normalize(
                        text.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
    }

    @SuppressWarnings("unchecked")
    private static void addRole(Map<String, Object> rbac, String capability, String role) {
        List<Object> roles = rbac.get(capability) instanceof List<?> raw
                ? new ArrayList<>((List<Object>) raw)
                : new ArrayList<>();
        if (!roles.contains(role)) {
            roles.add(role);
            rbac.put(capability, roles);
        }
    }

    private static boolean hasApprovalBlock(Map<String, Object> map) {
        if (!(map.get("blocks") instanceof List<?> blocks)) {
            return false;
        }
        return blocks.stream().anyMatch(block -> block instanceof Map<?, ?> blockMap
                && APPROVAL_WORKITEM_BLOCK.equals(String.valueOf(blockMap.get("type"))));
    }

    /**
     * Models often write the email field itself ("manager_email") instead of
     * the validator's record reference ("record.manager_email"). Rewrite only
     * when the token exactly matches a declared string field, so literal
     * emails, creator and config references are untouched. This avoids a
     * three-attempt repair loop for the common manager-email prompt.
     */
    @SuppressWarnings("unchecked")
    private void normalizeNotifyRecipients(Map<String, Object> map) {
        if (!(map.get("blocks") instanceof List<?> blocks)
                || !(map.get("entities") instanceof List<?> entities)) {
            return;
        }
        Set<String> stringFields = new LinkedHashSet<>();
        for (Object entity : entities) {
            if (entity instanceof Map<?, ?> entityMap
                    && entityMap.get("fields") instanceof List<?> fields) {
                for (Object field : fields) {
                    if (field instanceof Map<?, ?> fieldMap
                            && "string".equalsIgnoreCase(String.valueOf(fieldMap.get("type")))
                            && fieldMap.get("name") instanceof String name) {
                        stringFields.add(name);
                    }
                }
            }
        }
        if (stringFields.isEmpty()) {
            return;
        }
        boolean changed = false;
        List<Map<String, Object>> updatedBlocks = new ArrayList<>();
        for (Object blockItem : blocks) {
            if (!(blockItem instanceof Map<?, ?> rawBlock)) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>((Map<String, Object>) rawBlock);
            if ("notify".equals(String.valueOf(block.get("type")))
                    && block.get("params") instanceof Map<?, ?> rawParams) {
                Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) rawParams);
                if (params.get("to") instanceof List<?> recipients) {
                    List<Object> normalized = new ArrayList<>();
                    boolean blockChanged = false;
                    for (Object recipient : recipients) {
                        if (recipient instanceof String token) {
                            String candidate = token.trim();
                            if (!candidate.contains("@")
                                    && !candidate.equalsIgnoreCase("creator")
                                    && !candidate.startsWith("record.")
                                    && !candidate.startsWith("config.")
                                    && stringFields.contains(candidate)) {
                                normalized.add("record." + candidate);
                                blockChanged = true;
                                log.info("AI draft: notify recipient '{}' rewritten to 'record.{}'",
                                        candidate, candidate);
                                continue;
                            }
                        }
                        normalized.add(recipient);
                    }
                    if (blockChanged) {
                        params.put("to", normalized);
                        block.put("params", params);
                        changed = true;
                    }
                }
            }
            updatedBlocks.add(block);
        }
        if (changed) {
            map.put("blocks", updatedBlocks);
        }
    }

    /**
     * Draft models often emit notify/external_call blocks that no hook ever
     * reaches (so the app installs but the email/call never happens), and they
     * drop the polling schedule even when the prompt says "cứ 5 phút". Repair
     * the unambiguous cases: schedule from the prompt interval, hook an
     * otherwise-dead notify on onCreate/onDecide, hook a non-scheduled call on
     * onCreate. All remain reviewable in the composer.
     */
    @SuppressWarnings("unchecked")
    private void normalizeWorkflowTriggers(Map<String, Object> map, String userPrompt) {
        if (!(map.get("blocks") instanceof List<?> blocks)
                || !(map.get("entities") instanceof List<?> entities)
                || entities.isEmpty()) {
            return;
        }
        String firstEntity = entities.get(0) instanceof Map<?, ?> first
                && first.get("name") != null ? String.valueOf(first.get("name")) : null;
        if (firstEntity == null) {
            return;
        }
        boolean enabledField = entities.get(0) instanceof Map<?, ?> first
                && first.get("fields") instanceof List<?> fields
                && fields.stream().anyMatch(field -> field instanceof Map<?, ?> fieldMap
                        && "enabled".equalsIgnoreCase(String.valueOf(fieldMap.get("name")))
                        && "boolean".equals(String.valueOf(fieldMap.get("type"))));
        String interval = pollInterval(userPrompt);

        List<Map<String, Object>> updatedBlocks = new ArrayList<>();
        for (Object blockItem : blocks) {
            if (!(blockItem instanceof Map<?, ?> rawBlock)) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>((Map<String, Object>) rawBlock);
            if ("external_call".equals(String.valueOf(block.get("type"))) && interval != null) {
                Map<String, Object> params = block.get("params") instanceof Map<?, ?> rawParams
                        ? new LinkedHashMap<>((Map<String, Object>) rawParams)
                        : new LinkedHashMap<>();
                Object existing = params.get("schedule");
                if (!(existing instanceof Map<?, ?> schedule) || schedule.isEmpty()) {
                    Map<String, Object> schedule = new LinkedHashMap<>();
                    schedule.put("every", interval);
                    schedule.put("entity", firstEntity);
                    schedule.put("limit", 10);
                    if (enabledField) {
                        schedule.put("select", Map.of("enabled", true));
                    }
                    params.put("schedule", schedule);
                    block.put("params", params);
                }
            }
            updatedBlocks.add(block);
        }
        map.put("blocks", updatedBlocks);

        if (promptRequestsCreationNotify(userPrompt)) {
            promoteCreationNotifyToCreate(map, updatedBlocks);
        }

        Set<String> targets = new LinkedHashSet<>(hookTargets(map));
        boolean hasApproval = updatedBlocks.stream()
                .anyMatch(block -> "approval_workitem".equals(String.valueOf(block.get("type"))));
        for (Map<String, Object> block : updatedBlocks) {
            String id = String.valueOf(block.get("id"));
            if (targets.contains(id)) {
                continue;
            }
            String type = String.valueOf(block.get("type"));
            if ("external_call".equals(type)
                    && !(block.get("params") instanceof Map<?, ?> params && params.get("schedule") instanceof Map<?, ?>)) {
                appendHook(map, id, "onCreate");
                targets.add(id);
            } else if ("notify".equals(type)) {
                appendHook(map, id, hasApproval && isDecisionNotice(block) ? "onDecide" : "onCreate");
                targets.add(id);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void promoteCreationNotifyToCreate(Map<String, Object> map, List<Map<String, Object>> blocks) {
        Set<String> notifyIds = new LinkedHashSet<>();
        for (Map<String, Object> block : blocks) {
            if ("notify".equals(String.valueOf(block.get("type"))) && !isDecisionNotice(block)) {
                notifyIds.add(String.valueOf(block.get("id")));
            }
        }
        if (notifyIds.isEmpty() || !(map.get("entities") instanceof List<?> entities)) {
            return;
        }
        boolean changed = false;
        List<Map<String, Object>> updatedEntities = new ArrayList<>();
        for (Object entityItem : entities) {
            if (!(entityItem instanceof Map<?, ?> rawEntity)) {
                continue;
            }
            Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) rawEntity);
            if (entity.get("hooks") instanceof Map<?, ?> rawHooks) {
                Map<String, Object> hooks = new LinkedHashMap<>((Map<String, Object>) rawHooks);
                List<Object> onDecide = hooks.get("onDecide") instanceof List<?> current
                        ? new ArrayList<>(current)
                        : new ArrayList<>();
                boolean moved = onDecide.removeIf(target -> notifyIds.contains(String.valueOf(target)));
                if (moved) {
                    List<Object> onCreate = hooks.get("onCreate") instanceof List<?> current
                            ? new ArrayList<>(current)
                            : new ArrayList<>();
                    for (String id : notifyIds) {
                        if (!onCreate.contains(id)) {
                            onCreate.add(id);
                        }
                    }
                    hooks.put("onCreate", onCreate);
                    if (onDecide.isEmpty()) {
                        hooks.remove("onDecide");
                    } else {
                        hooks.put("onDecide", onDecide);
                    }
                    entity.put("hooks", hooks);
                    changed = true;
                }
            }
            updatedEntities.add(entity);
        }
        if (changed) {
            map.put("entities", updatedEntities);
            log.info("AI draft: prompt requests email on create; moved creation notify hook from onDecide to onCreate");
        }
    }

    private String pollInterval(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        Matcher matcher = POLL_INTERVAL.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        String amount = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        String unit = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
        long value;
        try {
            value = Long.parseLong(amount);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (value <= 0) {
            return null;
        }
        String normalizedUnit = java.text.Normalizer.normalize(
                unit.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        java.time.Duration duration = switch (normalizedUnit) {
            case "phut", "minute", "minutes" -> java.time.Duration.ofMinutes(value);
            case "gio", "hour", "hours" -> java.time.Duration.ofHours(value);
            case "ngay", "day", "days" -> java.time.Duration.ofDays(value);
            default -> null;
        };
        if (duration == null
                || duration.compareTo(java.time.Duration.ofSeconds(30)) < 0
                || duration.compareTo(java.time.Duration.ofDays(7)) > 0) {
            return null;
        }
        return duration.toString();
    }

    private boolean isDecisionNotice(Map<String, Object> block) {
        Map<?, ?> params = block.get("params") instanceof Map<?, ?> map ? map : Map.of();
        String text = (String.valueOf(params.get("title")) + " "
                + String.valueOf(params.get("subject"))).toLowerCase(java.util.Locale.ROOT);
        return text.contains("duyệt") || text.contains("từ chối") || text.contains("kết quả")
                || text.contains("approved") || text.contains("rejected") || text.contains("decision");
    }

    @SuppressWarnings("unchecked")
    private Set<String> hookTargets(Map<String, Object> map) {
        Set<String> targets = new LinkedHashSet<>();
        if (map.get("entities") instanceof List<?> entities) {
            for (Object entity : entities) {
                if (entity instanceof Map<?, ?> entityMap
                        && entityMap.get("hooks") instanceof Map<?, ?> hooks) {
                    for (Object value : hooks.values()) {
                        if (value instanceof List<?> list) {
                            list.forEach(target -> targets.add(String.valueOf(target)));
                        }
                    }
                }
            }
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private void appendHook(Map<String, Object> map, String blockId, String hookKey) {
        if (!(map.get("entities") instanceof List<?> rawEntities) || rawEntities.isEmpty()
                || !(rawEntities.get(0) instanceof Map<?, ?> rawFirst)) {
            return;
        }
        Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) rawFirst);
        Map<String, Object> hooks = entity.get("hooks") instanceof Map<?, ?> current
                ? new LinkedHashMap<>((Map<String, Object>) current)
                : new LinkedHashMap<>();
        List<Object> targets = hooks.get(hookKey) instanceof List<?> current
                ? new ArrayList<>(current)
                : new ArrayList<>();
        if (!targets.contains(blockId)) {
            targets.add(blockId);
        }
        hooks.put(hookKey, targets);
        entity.put("hooks", hooks);
        List<Object> updated = new ArrayList<>(rawEntities);
        updated.set(0, entity);
        map.put("entities", updated);
    }

    /** Block type -> id, only when exactly one block of that type exists. */
    private Map<String, String> uniqueBlockIdsByType(List<Map<String, Object>> blocks) {
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            String type = String.valueOf(block.get("type"));
            String id = String.valueOf(block.get("id"));
            counts.merge(type, 1, Integer::sum);
            ids.putIfAbsent(type, id);
        }
        ids.entrySet().removeIf(entry -> counts.getOrDefault(entry.getKey(), 0) > 1);
        return ids;
    }

    /**
     * Small models often target a hook at the block *type* ("approval_workitem")
     * instead of the block id. When that type is unambiguous, resolve it so the
     * draft stays installable; ambiguous types stay untouched for the validator.
     */
    @SuppressWarnings("unchecked")
    private void rewriteHooks(Map<String, Object> map, Map<String, String> droppedApprovalIds,
            Map<String, String> uniqueTypeIds) {
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
                            String resolved = uniqueTypeIds.getOrDefault(value, value);
                            remapped.add(droppedApprovalIds.getOrDefault(resolved, resolved));
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

    /**
     * Draft models frequently emit bare view prefixes ("registry", "form") or
     * stale targets. Repair the unambiguous cases (prefix + first entity/report)
     * and drop the rest so the composer opens a valid manifest.
     */
    @SuppressWarnings("unchecked")
    private void normalizeGeneratedUi(Map<String, Object> map) {
        Set<String> entityNames = new LinkedHashSet<>();
        if (map.get("entities") instanceof List<?> entities) {
            for (Object entity : entities) {
                if (entity instanceof Map<?, ?> entityMap && entityMap.get("name") != null) {
                    entityNames.add(String.valueOf(entityMap.get("name")));
                }
            }
        }
        Set<String> reportViews = new LinkedHashSet<>();
        Set<String> assistantBlocks = new LinkedHashSet<>();
        if (map.get("blocks") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (!(block instanceof Map<?, ?> blockMap)) {
                    continue;
                }
                String type = String.valueOf(blockMap.get("type"));
                if ("report".equals(type) && blockMap.get("params") instanceof Map<?, ?> params
                        && params.get("views") instanceof List<?> views) {
                    for (Object view : views) {
                        if (view instanceof Map<?, ?> viewMap && viewMap.get("name") != null) {
                            reportViews.add(String.valueOf(viewMap.get("name")));
                        }
                    }
                }
                if ("assistant".equals(type) && blockMap.get("id") != null) {
                    assistantBlocks.add(String.valueOf(blockMap.get("id")));
                }
            }
        }

        Map<String, Object> ui = map.get("ui") instanceof Map<?, ?> rawUi
                ? new LinkedHashMap<>((Map<String, Object>) rawUi)
                : new LinkedHashMap<>();
        Set<String> views = new LinkedHashSet<>();
        if (ui.get("views") instanceof List<?> rawViews) {
            for (Object rawView : rawViews) {
                if (!(rawView instanceof String view) || view.isBlank()) {
                    continue;
                }
                String[] parts = view.split(":", 2);
                String prefix = parts[0];
                String target = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
                if (target == null) {
                    target = switch (prefix) {
                        case "registry", "form", "timeline" -> first(entityNames);
                        case "report" -> first(reportViews);
                        case "assistant" -> first(assistantBlocks);
                        default -> null;
                    };
                }
                boolean valid = switch (prefix) {
                    case "registry", "form", "timeline" -> target != null && entityNames.contains(target);
                    case "report" -> target != null && reportViews.contains(target);
                    case "assistant" -> target != null && assistantBlocks.contains(target);
                    default -> false;
                };
                if (valid) {
                    views.add(prefix + ":" + target);
                }
            }
        }
        if (views.isEmpty()) {
            for (String entity : entityNames) {
                views.add("registry:" + entity);
                views.add("form:" + entity);
            }
        }
        ui.put("views", List.copyOf(views));
        map.put("ui", ui);
    }

    private String first(Set<String> values) {
        return values.isEmpty() ? null : values.iterator().next();
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
