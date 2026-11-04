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
import com.openvie.api.bizapp.repository.BizAppDraftRepository;
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
import java.util.UUID;

/**
 * Service managing AI manifest drafting, stateless validation, and draft lifecycle (Workstream A).
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "BIZAPP-DRAFT")
public class BizAppDraftService {

    private final BizAppDraftRepository draftRepository;
    private final BizAppManifestValidator validator;
    private final BizAppManifestParser parser;
    private final AiInferenceApi aiInferenceApi;
    private final BizAppProperties properties;
    private final BizAppRuntime runtime;
    private final ObjectMapper objectMapper;

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
                4. entities: array of entities with name, label, fields.
                5. Field types: "string", "number", "boolean", "enum", "date", "ref".
                6. storage: "jsonb" (never "typed").
                7. rbac: map of capabilities to role arrays (allowed: "ORG_OWNER", "WORKSPACE_ADMIN", "MEMBER").
                8. NO SQL anywhere in the manifest! Reports must use declarative views with groupBy, metrics, filters, orderBy.
                9. Never include credentials or private keys in the manifest.
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
                manifestMap = objectMapper.readValue(currentOutput, new TypeReference<Map<String, Object>>() {});
                validation = validateStateless(manifestMap);
                if (validation.ok()) {
                    break;
                }
                userPrompt += "\n\nThe generated manifest had validation errors: " + String.join("; ", validation.errors()) + ". Please fix them.";
            } catch (Exception ex) {
                validation = new ValidationResult(false, List.of("JSON parse/generation error: " + ex.getMessage()));
            }
        }

        String title = "Draft: " + request.prompt().substring(0, Math.min(request.prompt().length(), 40));
        if (manifestMap.containsKey("name") && manifestMap.get("name") instanceof Map<?, ?> mName && mName.get("en") != null) {
            title = String.valueOf(mName.get("en"));
        }

        BizAppDraft draft = new BizAppDraft();
        draft.setOrgId(orgId);
        draft.setCreatedBy(actorId);
        draft.setSource("ai");
        draft.setTitle(title);
        draft.setManifest(manifestMap);
        draft.setValidation(Map.of("ok", validation.ok(), "errors", validation.errors()));
        draft.setMeta(Map.of("generator", "ai", "model", "vylinh", "prompt", request.prompt()));

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

        return toView(saved);
    }

    @Transactional
    public void deleteDraft(UUID orgId, UUID tenantId, UUID actorId, UUID draftId) {
        BizAppDraft draft = draftRepository.findByIdAndOrgId(draftId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + draftId));
        draftRepository.delete(draft);
        runtime.audit(tenantId, actorId, LogAction.BIZAPP_DRAFT_DELETED, "bizapp_draft",
                draftId, Map.of("title", draft.getTitle()));
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
