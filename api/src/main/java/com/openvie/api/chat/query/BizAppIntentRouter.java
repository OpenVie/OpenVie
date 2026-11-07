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

package com.openvie.api.chat.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.ActionCapabilities;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppActionOffer;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.service.BizAppSlotAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.chat.model.ChatMessage;
/**
 * Deterministic intent gate and structured LLM router for conversational BizApps (W1.3, W2.1).
 */
@Component
@Slf4j(topic = "INTENT-ROUTER")
public class BizAppIntentRouter {

    /**
     * Inline structured-output schema handed to the inference service. Mirrors
     * contracts/bizapp/bizapp_intent.schema.json (extended with the record
     * intents and slot fields the runtime contract supports).
     */
    private static final String INTENT_JSON_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "$id": "https://openvie.local/contracts/bizapp/bizapp_intent.schema.json",
              "type": "object",
              "required": ["intent", "confidence"],
              "properties": {
                "intent": {
                  "type": "string",
                  "enum": ["NONE", "LIST_APPS", "APP_DETAIL", "APP_DATA", "INSTALL", "UNINSTALL",
                    "SUGGEST", "CREATE_APP", "APP_RECORD_CREATE", "APP_RECORD_UPDATE",
                    "APP_RECORD_ARCHIVE", "APP_RECORD_SUBMIT"]
                },
                "appKind": { "type": "string" },
                "entity": { "type": "string" },
                "fields": { "type": "object" },
                "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
                "reply": { "type": "string" },
                "reason": { "type": "string" }
              }
            }
            """;

    public enum Intent {
        NONE,
        LIST_APPS,
        APP_DETAIL,
        APP_DATA,
        INSTALL,
        UNINSTALL,
        SUGGEST,
        CREATE_APP,
        APP_RECORD_CREATE,
        APP_RECORD_UPDATE,
        APP_RECORD_ARCHIVE,
        APP_RECORD_SUBMIT
    }

    public record RoutingResult(
            Intent intent,
            String appKind,
            String entity,
            BigDecimal confidence,
            String reply,
            String reason,
            AppActionOffer offer,
            Map<String, Object> actionCard
    ) {
        public RoutingResult(Intent intent, String appKind, BigDecimal confidence, String reply, String reason, AppActionOffer offer, Map<String, Object> actionCard) {
            this(intent, appKind, null, confidence, reply, reason, offer, actionCard);
        }

        public static RoutingResult none() {
            return new RoutingResult(Intent.NONE, null, null, BigDecimal.ZERO, null, "Default RAG route", null, null);
        }
    }

    /** An installed app chosen semantically from the manifest catalog. */
    private record AppSelection(String kind, String entity) {}

    private static final Pattern ADMIN_VERBS = Pattern.compile(
            "\\b(cai|cai dat|install|go|go cai dat|uninstall|xoa app|remove app|danh sach app|list apps?|show apps?|xem app|tao app|lap app|them app|create app|build app|compose app|goi y app|suggest apps?|ung dung|marketplace)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WRITE_VERBS = Pattern.compile(
            "\\b(them|tao|lap|nhap|ghi|ghi nhan|luu|luu tru|cap nhat|sua|doi|xoa|go bo|gui duyet|trinh duyet|"
            + "add|create|insert|log|save|update|edit|change|delete|remove|archive|submit|send for approval|"
            + "set up|set)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CREATE_VERBS = Pattern.compile(
            "\\b(them|tao|lap|nhap|ghi|ghi nhan|luu|luu tru|dang ky|xin|gui|"
            + "add|create|insert|log|save|register|request|report|notify)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GENERIC_APP_VOCAB = Pattern.compile(
            "\\b(app|apps|ung dung|phan mem|ban ghi|record|records|item|items|du lieu|data|thuc the|entity|entities|truong|fields?|cai dat|install|go cai dat|uninstall|marketplace|catalog|danh muc)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MANAGEMENT_NEED = Pattern.compile(
            "\\b(quan ly|theo doi|kiem soat|giam sat|quan tri|luu tru thong tin|manage|track|monitor)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Desire/politeness phrases that introduce an operational request in any domain. */
    private static final Pattern REQUEST_VERBS = Pattern.compile(
            "\\b(muon|can|giup|co the|please|want|need|would like|can you|could you)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Greetings, thanks, acknowledgements and identity turns need no app LLM. */
    private static final Pattern CHIT_CHAT = Pattern.compile(
            "^\\s*(?:hi+|hello+|hey+|yo|alo+|chao|xin chao|"
            + "good (?:morning|afternoon|evening)|bye+|tam biet|hen gap lai|"
            + "cam on|thanks?|thank you|ok(?:ay)?|oke|vang|da|uhm*|u+|"
            + "who are you|ban la ai|may la ai|how are you|(?:ban\\s+)?khoe\\s+khong)"
            + "\\s*[.?!…~]*\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern READ_VERBS = Pattern.compile(
            "\\b(xem|liet ke|danh sach|tim|tra cuu|show|list|view|find|search)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Questions that ask for knowledge rather than an operational action. */
    private static final Pattern INFO_REQUEST = Pattern.compile(
            "\\b(biet|hoi|cho biet|thong tin|giai thich|tell me|explain|what|how|why|when|where|which|bao nhieu)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern UPDATE_VERBS = Pattern.compile(
            "\\b(cap nhat|sua|doi|update|edit|change)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ARCHIVE_VERBS = Pattern.compile(
            "\\b(xoa|go bo|xoa bo|archive|delete|remove)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SUBMIT_VERBS = Pattern.compile(
            "\\b(gui duyet|trinh duyet|submit|send for approval)\\b",
            Pattern.CASE_INSENSITIVE
    );


    private static final Pattern PROCEDURE_KEYWORDS = Pattern.compile(
            "\\b(quy trinh|huong dan|chinh sach|cach|cach nao|lam sao|lam the nao|nhu the nao|quy dinh|thu tuc|policy|guideline|procedure|how to)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HARD_POLICY_KEYWORDS = Pattern.compile(
            "\\b(quy trinh|huong dan|chinh sach|quy dinh|thu tuc|policy|guideline|procedure)\\b",
            Pattern.CASE_INSENSITIVE
    );


    private static final Pattern DETAIL_KEYWORDS = Pattern.compile(
            "\\b(truong|truong nao|fields?|thuoc tinh|thuc the|entities|entity|cau truc|chi tiet|detail|details|thong tin|gom nhung)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Follow-up asking which data is required to create a record in the active app. */
    private static final Pattern FIELD_INFO_REQUEST = Pattern.compile(
            "\\b(thong tin nao|thong tin gi|can cung cap|cung cap (?:nhung )?thong tin|"
            + "nhung thong tin nao|truong nao|field nao|can nhap|nhap gi|"
            + "what information|which (?:information|fields?)|"
            + "what (?:do i|should i) (?:need|provide)|what details|details do you need|what fields)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Structured-output contract for semantic app selection. */
    private static final String APP_SELECTOR_JSON_SCHEMA = """
            {
              "type": "object",
              "required": ["appKind", "confidence"],
              "properties": {
                "appKind": { "type": ["string", "null"] },
                "entity": { "type": ["string", "null"] },
                "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 }
              }
            }
            """;

    private static final int SELECTOR_MAX_APPS = 20;
    private static final int SELECTOR_MAX_ENTITIES_PER_APP = 10;
    private static final int SELECTOR_MAX_FIELDS_PER_ENTITY = 40;
    private static final double SELECTOR_CONFIDENCE_THRESHOLD = 0.6;

    private static final Pattern FILLER_KEYWORDS = Pattern.compile(
            "\\b(cho\\s+(?:toi\\s+)?(?:ty|ti|chut|da)|doi\\s+(?:toi\\s+)?(?:ty|ti|chut|da)|khoan\\s+da|wait|hold\\s+on|one\\s+moment)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern RECORD_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static boolean isFillerTurn(String normalized) {
        if (normalized == null) return false;
        return FILLER_KEYWORDS.matcher(normalized).find();
    }

    // App-lifecycle phrasings address THE APP as an object ("gỡ ứng dụng đặt
    // lịch", "cài đặt app quản lý...", "uninstall the app") — not a record
    // inside it. They are matched against the diacritics-stripped normalized
    // form (see removeDiacritics), so the alternations stay plain ASCII.
    private static final Pattern UNINSTALL_APP_REQUEST = Pattern.compile(
            "\\b(?:go|xoa|uninstall|remove|delete|bo)\\s*(?:bo\\s+|the\\s+|a\\s+|an\\s+)?(?:[a-z]{1,4}\\s+){0,2}"
                    + "(?:ung\\s+dung|ap\\s+dung|apps?|phan\\s+mem)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INSTALL_APP_REQUEST = Pattern.compile(
            "\\b(?:cai(?:\\s+dat)?|them(?=\\s)|tao(?=\\s)|lap(?=\\s)|install)\\s*"
                    + "(?:moi\\s+|the\\s+|a\\s+|an\\s+)?(?:[a-z]{1,4}\\s+){0,2}"
                    + "(?:ung\\s+dung|ap\\s+dung|apps?|phan\\s+mem)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** True when the turn asks to REMOVE the app itself (vs. a record inside it). */
    public static boolean isUninstallAppRequest(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        return UNINSTALL_APP_REQUEST.matcher(normalized).find();
    }

    /** True when the turn asks to INSTALL/set up the app itself. */
    public static boolean isInstallAppRequest(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        return INSTALL_APP_REQUEST.matcher(normalized).find();
    }
    private final BizAppRuntimeApi bizAppRuntimeApi;
    private final AiInferenceApi aiInferenceApi;
    private final ObjectMapper objectMapper;
    private final BizAppSlotAgent slotAgent;

    public BizAppIntentRouter(
            @org.springframework.context.annotation.Lazy BizAppRuntimeApi bizAppRuntimeApi,
            AiInferenceApi aiInferenceApi,
            ObjectMapper objectMapper,
            BizAppSlotAgent slotAgent
    ) {
        this.bizAppRuntimeApi = bizAppRuntimeApi;
        this.aiInferenceApi = aiInferenceApi;
        this.objectMapper = objectMapper;
        this.slotAgent = slotAgent;
    }
    @Value("${app.chat.bizapp-actions.enabled:false}")
    private boolean actionsEnabled = false;

    @Value("${app.chat.bizapp-records.enabled:false}")
    private boolean recordActionsEnabled = false;

    @Value("${app.chat.intent-confidence-threshold:0.75}")
    private double confidenceThreshold = 0.75;

    @Value("${app.chat.intent-classify-all-turns:false}")
    private boolean classifyAllTurns = false;

    public void setActionsEnabled(boolean actionsEnabled) {
        this.actionsEnabled = actionsEnabled;
    }

    public void setRecordActionsEnabled(boolean recordActionsEnabled) {
        this.recordActionsEnabled = recordActionsEnabled;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public void setClassifyAllTurns(boolean classifyAllTurns) {
        this.classifyAllTurns = classifyAllTurns;
    }

    public RoutingResult route(
            UUID tenantId,
            UUID orgId,
            UUID actorId,
            String role,
            String content,
            String locale,
            UUID sessionId
    ) {
        return route(tenantId, orgId, actorId, role, content, locale, sessionId, List.of());
    }

    public RoutingResult route(
            UUID tenantId,
            UUID orgId,
            UUID actorId,
            String role,
            String content,
            String locale,
            UUID sessionId,
            List<ChatMessage> priorMessages
    ) {
        // RAG-neutrality: short-circuit before gate to introduce 0 latency when actions disabled
        if (!actionsEnabled && !recordActionsEnabled) {
            return RoutingResult.none();
        }

        if (content == null || content.isBlank()) {
            return RoutingResult.none();
        }

        List<InstalledAppSummary> installed = bizAppRuntimeApi.listInstalledSummary(tenantId);
        List<MarketplaceTemplateSummary> catalog = bizAppRuntimeApi.listMarketplaceSummary(tenantId);
        Map<String, ActionCapabilities> capabilities = bizAppRuntimeApi.listActionCapabilities(tenantId, role);
        // Apps the caller cannot read stay out of selection, prompts and cards;
        // the raw list is still used for existence checks (install/uninstall).
        List<InstalledAppSummary> visibleInstalled = visibleInstalled(installed, capabilities);
        Map<String, String> aliasToKind = buildAliasToKindMap(visibleInstalled, catalog);

        Set<String> knownKinds = new HashSet<>();
        visibleInstalled.forEach(i -> knownKinds.add(i.kind().toLowerCase(Locale.ROOT)));
        catalog.forEach(c -> knownKinds.add(c.kind().toLowerCase(Locale.ROOT)));
        String normalized = removeDiacritics(content.toLowerCase(Locale.ROOT));

        // 1. Resolve app kind from content
        String directKind = resolveAppKind(content, knownKinds, aliasToKind);
        // True when the kind came from THIS message (as opposed to conversation lookback)
        boolean directFromContent = directKind != null;

        // 2. Multi-turn lookback window: if content alone doesn't have an app kind, check prior messages!
        boolean fromContext = false;
        if (directKind == null && priorMessages != null && !priorMessages.isEmpty()) {
            directKind = findRecentAppContext(priorMessages, knownKinds, aliasToKind);
            if (directKind != null) {
                fromContext = true;
            }
        }

        // Procedure/policy/guideline questions must always go to RAG,
        // unless it is an inquiry about managing/tracking a workflow domain.
        if (PROCEDURE_KEYWORDS.matcher(normalized).find()
                && (!MANAGEMENT_NEED.matcher(normalized).find() || HARD_POLICY_KEYWORDS.matcher(normalized).find())) {
            return RoutingResult.none();
        }

        // Greetings/thanks/identity turns have no app intent. Returning before
        // the selector/classifier keeps chit-chat on the RAG side's fixed
        // template with zero LLM calls, even when classify-all-turns is on.
        if (directKind == null && isChitChat(normalized)) {
            return RoutingResult.none();
        }

        // 3. Manifest-driven semantic app selection. Alias/trigger matching is
        // fast but only reaches apps whose kind, name, trigger or entity happens
        // to appear verbatim in the message. When that fails, ask a small
        // structured-output model to match the request against every installed
        // app's name/description/triggers/entities/fields, so any app can be
        // reached by meaning without hardcoding its domain.
        AppSelection semantic = null;
        if (directKind == null
                && !visibleInstalled.isEmpty()
                && (looksOperational(normalized) || !isInformationalQuestion(normalized))) {
            semantic = selectInstalledApp(content, visibleInstalled, aliasToKind, locale);
            if (semantic != null) {
                directKind = semantic.kind();
            }
        }
        boolean semanticMatch = semantic != null;
        String preferredEntity = semantic != null ? semantic.entity() : null;

        boolean inActiveContext = (directKind != null);

        if (!classifyAllTurns && !matchesDeterministicGate(content, aliasToKind.keySet()) && !inActiveContext) {
            return offerCreateAppForUncoveredAction(
                    content, normalized, locale, sessionId, "Uncovered operational request");
        }
        // Handle filler/pause turn when in active app context (e.g. "Chờ tôi tý tôi đi lấy thông tin đã")
        if (inActiveContext && isFillerTurn(normalized)) {
            String reply = (locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi"))
                    ? "Dạ vâng, bạn cứ chuẩn bị thông tin nhé. Khi nào sẵn sàng hãy gửi cho tôi!"
                    : "Sure, take your time! Send the details whenever you are ready.";
            return new RoutingResult(
                    Intent.APP_RECORD_CREATE,
                    directKind,
                    null,
                    BigDecimal.valueOf(0.98),
                    reply,
                    "Filler pause turn in active app context",
                    null,
                    null
            );
        }

        // Fast deterministic detail match
        if (!fromContext && directKind != null && DETAIL_KEYWORDS.matcher(normalized).find() && !WRITE_VERBS.matcher(normalized).find()) {
            return new RoutingResult(
                    Intent.APP_DETAIL,
                    directKind,
                    null,
                    BigDecimal.valueOf(0.98),
                    "Chi tiết ứng dụng " + directKind,
                    "Deterministic app detail match",
                    null,
                    null
            );
        }

        // Manifest triggers carry action semantics: a message that names an app
        // through one of its triggers is an action request, unless it is phrased
        // as an information question ("X là gì?", "ở đâu?", "khi nào?"...),
        // which still flows through the LLM classifier.
        boolean triggerInContent = directFromContent
                && containsAppActionTrigger(normalized, directKind, aliasToKind)
                && !isInformationalQuestion(normalized);

        // A semantic selection is only allowed to take the fast create path
        // when the turn is an action/desire rather than a read or an
        // update/archive/submit request (those keep their intent semantics and
        // flow through the classifier with the selected app as a hint).
        boolean readRequest = READ_VERBS.matcher(normalized).find();
        boolean nonCreateVerb = UPDATE_VERBS.matcher(normalized).find()
                || ARCHIVE_VERBS.matcher(normalized).find()
                || SUBMIT_VERBS.matcher(normalized).find();
        boolean semanticCreate = semanticMatch
                && !readRequest
                && !nonCreateVerb
                && (REQUEST_VERBS.matcher(normalized).find()
                    || CREATE_VERBS.matcher(normalized).find()
                    || triggerInContent);
        boolean semanticNonCreate = semanticMatch && !semanticCreate;

        // Fast deterministic record create match or prompt to install, unless
        // the turn is an app-lifecycle request: uninstall phrasing must reach
        // the classifier (where the INSTALL/UNINSTALL arm and guardrails live),
        // and install phrasing on an ALREADY-INSTALLED app must not be turned
        // into a record prompt. The deterministic install prompt for a catalog
        // app that is not installed yet is kept (zero LLM latency there).
        final String lifecycleKind = directKind;
        boolean targetInstalled = installed.stream().anyMatch(i -> i.kind().equalsIgnoreCase(lifecycleKind));
        boolean appLifecycleTurn = isUninstallAppRequest(normalized)
                || (isInstallAppRequest(normalized) && targetInstalled);
        if (directKind != null
                && !semanticNonCreate
                && (WRITE_VERBS.matcher(normalized).find() || fromContext || triggerInContent || semanticCreate)
                && !appLifecycleTurn) {
            final String targetAppKind = directKind;
            var instOpt = visibleInstalled.stream().filter(i -> i.kind().equalsIgnoreCase(targetAppKind)).findFirst();
            if (instOpt.isPresent()) {
                InstalledAppSummary app = instOpt.get();
                if (!capabilityAllows(capabilities, app.kind(), Intent.APP_RECORD_CREATE)) {
                    return capabilityDenied(Intent.APP_RECORD_CREATE, app.kind(), role, locale);
                }
                String accumulatedText = buildAccumulatedUserText(priorMessages, content);
                EntityDetail targetEntity = preferredEntity != null
                        ? findEntity(preferredEntity, app.entityDetails())
                        : null;
                if (targetEntity == null) {
                    targetEntity = resolveEntity(accumulatedText, app.entityDetails());
                }
                if (targetEntity != null) {
                    if (fromContext && FIELD_INFO_REQUEST.matcher(normalized).find()) {
                        return new RoutingResult(
                                Intent.APP_RECORD_CREATE,
                                app.kind(),
                                targetEntity.name(),
                                BigDecimal.valueOf(0.98),
                                buildFieldInfoReply(app, targetEntity, locale),
                                "Field information request in active app context",
                                null,
                                null
                        );
                    }
                    Map<String, Object> fields = resolveRecordFields(priorMessages, content, targetEntity, app.name(), locale);

                    // If user is asking generally without any fields, prompt for details instead of failing or going to RAG
                    if (fields.isEmpty()) {
                        String appName = localizedAppName(app, locale);
                        String reply = (locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi"))
                                ? "Tôi có thể giúp bạn tạo một mục mới trong " + appName + ". Vui lòng cho tôi biết thông tin chi tiết để tôi chuẩn bị nhé!"
                                : "I can help you create a new entry in " + appName + ". Please provide the details so I can prepare it!";
                        return new RoutingResult(
                                Intent.APP_RECORD_CREATE,
                                app.kind(),
                                targetEntity.name(),
                                BigDecimal.valueOf(0.98),
                                reply,
                                "Prompt for record details",
                                null,
                                null
                        );
                    }

                    List<Map<String, String>> invalidList = validateRequiredFields(fields, targetEntity.fields());
                    if (!invalidList.isEmpty()) {
                        String reply = buildMissingFieldsPrompt(invalidList, fields, targetEntity, locale, app.name());
                        Map<String, Object> card = buildRecordActionCard(
                                "bizapp.record.create",
                                app.kind(),
                                app.name(),
                                targetEntity.name(),
                                targetEntity.label() != null ? targetEntity.label() : targetEntity.name(),
                                null,
                                fields,
                                buildFieldLabels(targetEntity.fields()),
                                "INVALID",
                                invalidList,
                                sessionId,
                                null,
                                role
                        );
                        return new RoutingResult(
                                Intent.APP_RECORD_CREATE,
                                app.kind(),
                                targetEntity.name(),
                                BigDecimal.valueOf(0.98),
                                reply,
                                "Prompt for remaining required fields",
                                null,
                                card
                        );
                    }

                    String payloadHash = computePayloadHash(fields);
                    String reply = "Tôi đã chuẩn bị thông tin tạo bản ghi mới trong " + app.name() + ".";

                    Map<String, Object> card = buildRecordActionCard(
                            "bizapp.record.create",
                            app.kind(),
                            app.name(),
                            targetEntity.name(),
                            targetEntity.label() != null ? targetEntity.label() : targetEntity.name(),
                            null,
                            fields,
                            buildFieldLabels(targetEntity.fields()),
                            "PROPOSED",
                            List.of(),
                            sessionId,
                            payloadHash,
                            role
                    );

                    return new RoutingResult(
                            Intent.APP_RECORD_CREATE,
                            app.kind(),
                            targetEntity.name(),
                            BigDecimal.valueOf(0.98),
                            reply,
                            "Deterministic record create match",
                            null,
                            card
                    );
                }
            } else {
                // Installed but invisible to this role: never offer it as an
                // install, and never fall through to the classifier as if the
                // app did not exist.
                boolean installedButHidden = installed.stream()
                        .anyMatch(i -> i.kind().equalsIgnoreCase(targetAppKind));
                if (installedButHidden) {
                    return capabilityDenied(Intent.APP_RECORD_CREATE, targetAppKind, role, locale);
                }
                // App is not installed yet: if available in catalog, prompt to install!
                var catOpt = catalog.stream().filter(c -> c.kind().equalsIgnoreCase(targetAppKind)).findFirst();
                if (catOpt.isPresent()) {
                    MarketplaceTemplateSummary tmpl = catOpt.get();
                    AppActionOffer offer = bizAppRuntimeApi.offerInstall(tenantId, orgId, directKind, actorId);
                    if (offer != null) {
                        Map<String, Object> card = buildActionCard(offer, "PROPOSED", sessionId);
                        String reply = (locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi"))
                                ? "Ứng dụng " + tmpl.name() + " hiện chưa được cài đặt trong không gian làm việc này. Bạn có muốn cài đặt ứng dụng để sử dụng tính năng này không?"
                                : "Application " + tmpl.name() + " is currently not installed in this workspace. Would you like to install it to use this feature?";
                        return new RoutingResult(
                                Intent.INSTALL,
                                directKind,
                                null,
                                BigDecimal.valueOf(0.98),
                                reply,
                                "Prompt to install app before record operation",
                                offer,
                                card
                        );
                    }
                }
            }
        }

        String systemPrompt = buildSystemPrompt(visibleInstalled, catalog, role, locale, content,
                directKind, aliasToKind, capabilities);
        String prompt = "User statement: " + content;

        try {
            var structuredResponse = aiInferenceApi.completeJson(new AiInferenceApi.StructuredJsonRequest(
                    prompt,
                    systemPrompt,
                    INTENT_JSON_SCHEMA,
                    "vylinh",
                    0.1f,
                    512
            ));

            String json = structuredResponse.jsonOutput();
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return directKind == null
                        ? offerCreateAppForUncoveredAction(
                                content, normalized, locale, sessionId, "Uncovered operational request")
                        : RoutingResult.none();
            }

            JsonNode root = objectMapper.readTree(json);
            String intentStr = root.path("intent").asText("NONE").toUpperCase(Locale.ROOT);
            Intent intent;
            try {
                intent = Intent.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                intent = Intent.NONE;
            }

            double conf = root.path("confidence").asDouble(0.0);
            BigDecimal confidence = BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, conf)))
                    .setScale(3, java.math.RoundingMode.HALF_UP);

            if (conf < confidenceThreshold || intent == Intent.NONE) {
                return directKind == null
                        ? offerCreateAppForUncoveredAction(
                                content, normalized, locale, sessionId, "Uncovered operational request")
                        : RoutingResult.none();
            }

            String rawKind = root.path("appKind").asText(null);
            String appKind = resolveAppKind(rawKind != null ? rawKind : content, knownKinds, aliasToKind);
            if (appKind == null && directKind != null && isAppBoundIntent(intent)) {
                // The manifest selector already proved the app; let the
                // classifier decide the operation (update/read/data) without
                // requiring it to repeat the kind.
                appKind = directKind;
            }
            if (appKind == null && isAppBoundIntent(intent)) {
                return RoutingResult.none();
            }

            String reply = root.path("reply").asText("");
            String reason = root.path("reason").asText("");

            AppActionOffer offer = null;
            Map<String, Object> actionCard = null;
            final String targetKind = appKind;

            if (intent == Intent.INSTALL && targetKind != null) {
                boolean alreadyActive = installed.stream().anyMatch(i -> i.kind().equalsIgnoreCase(targetKind));
                if (alreadyActive) {
                    return RoutingResult.none();
                }
                offer = bizAppRuntimeApi.offerInstall(tenantId, orgId, targetKind, actorId);
                actionCard = buildActionCard(offer, "PROPOSED", sessionId);
            } else if (intent == Intent.UNINSTALL && targetKind != null) {
                boolean isActive = installed.stream().anyMatch(i -> i.kind().equalsIgnoreCase(targetKind));
                if (!isActive) {
                    return RoutingResult.none();
                }
                offer = bizAppRuntimeApi.offerUninstall(tenantId, targetKind, actorId);
                actionCard = buildActionCard(offer, "PROPOSED", sessionId);
            } else if (intent == Intent.SUGGEST) {
                actionCard = Map.of(
                        "type", "bizapp_action",
                        "actionId", UUID.randomUUID().toString(),
                        "kind", "bizapp.suggest",
                        "status", "PROPOSED",
                        "requiresRole", "MEMBER"
                );
            } else if (intent == Intent.CREATE_APP) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("type", "bizapp_action");
                card.put("actionId", UUID.randomUUID().toString());
                card.put("kind", "bizapp.draft");
                card.put("appName", rawKind != null && !rawKind.isBlank() ? rawKind : "Ứng dụng mới");
                card.put("status", "PROPOSED");
                card.put("requiresRole", "ORG_OWNER");
                Map<String, Object> payload = new LinkedHashMap<>();
                if (sessionId != null) {
                    payload.put("sessionId", sessionId.toString());
                }
                payload.put("hint", content);
                card.put("payload", payload);
                actionCard = card;
            } else if (intent == Intent.APP_RECORD_CREATE || intent == Intent.APP_RECORD_UPDATE
                    || intent == Intent.APP_RECORD_ARCHIVE || intent == Intent.APP_RECORD_SUBMIT) {
                if (!recordActionsEnabled) {
                    return RoutingResult.none();
                }
                if (!capabilityAllows(capabilities, targetKind, intent)) {
                    return capabilityDenied(intent, targetKind, role, locale);
                }
                var instOpt = visibleInstalled.stream().filter(i -> i.kind().equalsIgnoreCase(targetKind)).findFirst();
                if (instOpt.isEmpty()) {
                    return RoutingResult.none();
                }
                InstalledAppSummary app = instOpt.get();
                EntityDetail targetEntity = resolveEntity(root.path("entity").asText(null), app.entityDetails());
                if (targetEntity == null) {
                    targetEntity = resolveEntity(content, app.entityDetails());
                }
                if (targetEntity != null) {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    JsonNode fieldsNode = root.path("fields");
                    if (fieldsNode.isObject()) {
                        for (var it = fieldsNode.fields(); it.hasNext(); ) {
                            var e = it.next();
                            fields.put(e.getKey(), e.getValue().asText());
                        }
                    }
                    if (fields.isEmpty()) {
                        fields = resolveRecordFields(priorMessages, content, targetEntity, app.name(), locale);
                    } else {
                        fields = slotAgent.normalize(fields, targetEntity);
                        if (fields.isEmpty() && intent == Intent.APP_RECORD_CREATE) {
                            fields = resolveRecordFields(priorMessages, content, targetEntity, app.name(), locale);
                        }
                    }

                    List<Map<String, String>> invalidList = (intent == Intent.APP_RECORD_CREATE)
                            ? validateRequiredFields(fields, targetEntity.fields())
                            : List.of();
                    String status = invalidList.isEmpty() ? "PROPOSED" : "INVALID";
                    String payloadHash = invalidList.isEmpty() ? computePayloadHash(fields) : null;

                    UUID recordId = null;
                    if (intent != Intent.APP_RECORD_CREATE) {
                        recordId = resolveRecordId(content, priorMessages, app.kind(), targetEntity.name());
                        if (recordId == null) {
                            return missingRecordResult(intent, app.kind(), targetEntity.name(), locale);
                        }
                        if (intent == Intent.APP_RECORD_UPDATE && fields.isEmpty()) {
                            return askWhatToChangeResult(intent, app.kind(), targetEntity.name(), locale);
                        }
                    }

                    String cardKind = switch (intent) {
                        case APP_RECORD_UPDATE -> "bizapp.record.update";
                        case APP_RECORD_ARCHIVE -> "bizapp.record.archive";
                        case APP_RECORD_SUBMIT -> "bizapp.record.submit";
                        default -> "bizapp.record.create";
                    };

                    actionCard = buildRecordActionCard(
                            cardKind,
                            app.kind(),
                            app.name(),
                            targetEntity.name(),
                            targetEntity.label() != null ? targetEntity.label() : targetEntity.name(),
                            recordId,
                            fields,
                            buildFieldLabels(targetEntity.fields()),
                            status,
                            invalidList,
                            sessionId,
                            payloadHash,
                            role
                    );
                }
            }

            return new RoutingResult(intent, appKind, null, confidence, reply, reason, offer, actionCard);

        } catch (Exception ex) {
            log.warn("Intent classification failed: {}", ex.getMessage());
            return directKind == null
                    ? offerCreateAppForUncoveredAction(
                            content, normalized, locale, sessionId, "Uncovered operational request")
                    : RoutingResult.none();
        }
    }

    /** Apps the caller's role may read; null capabilities keep the legacy unfiltered view. */
    private static List<InstalledAppSummary> visibleInstalled(
            List<InstalledAppSummary> installed,
            Map<String, ActionCapabilities> capabilities
    ) {
        if (capabilities == null || installed == null) {
            return installed;
        }
        return installed.stream()
                .filter(app -> {
                    ActionCapabilities caps = capabilities.get(app.kind());
                    return caps == null || caps.read();
                })
                .toList();
    }

    /** True when the caller's role may execute this record intent; unknown apps stay allowed. */
    private static boolean capabilityAllows(
            Map<String, ActionCapabilities> capabilities,
            String appKind,
            Intent intent
    ) {
        if (capabilities == null || appKind == null) {
            return true;
        }
        ActionCapabilities caps = capabilities.get(appKind);
        if (caps == null) {
            return true;
        }
        return switch (intent) {
            case APP_RECORD_CREATE -> caps.create();
            case APP_RECORD_UPDATE -> caps.update();
            case APP_RECORD_ARCHIVE -> caps.archive();
            case APP_RECORD_SUBMIT -> caps.submit();
            default -> true;
        };
    }

    private static RoutingResult capabilityDenied(Intent intent, String appKind, String role, String locale) {
        String reply = isVietnamese(locale)
                ? "Vai trò của bạn không được phép thực hiện hành động này trong ứng dụng này."
                : "Your role is not allowed to perform this action in this app.";
        return new RoutingResult(intent, appKind, null, BigDecimal.ONE, reply,
                "Manifest capability denied for role " + role, null, null);
    }

    private static RoutingResult missingRecordResult(Intent intent, String appKind, String entity, String locale) {
        String reply = isVietnamese(locale)
                ? "Mình cần biết bản ghi cụ thể. Hãy mở ứng dụng và chọn bản ghi, hoặc cho mình biết bản ghi vừa tạo."
                : "I need the specific record. Open the app and choose it, or tell me which record you just created.";
        return new RoutingResult(intent, appKind, entity, BigDecimal.ONE, reply,
                "No record id in this conversation", null, null);
    }

    private static RoutingResult askWhatToChangeResult(Intent intent, String appKind, String entity, String locale) {
        String reply = isVietnamese(locale)
                ? "Bạn muốn cập nhật trường nào của bản ghi này?"
                : "Which field of this record would you like to update?";
        return new RoutingResult(intent, appKind, entity, BigDecimal.ONE, reply,
                "No fields to update were provided", null, null);
    }

    private static boolean isVietnamese(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi");
    }

    /**
     * Resolves the record a chat action targets: an explicit UUID in the
     * current message wins, otherwise the most recent matching record action
     * card in the conversation (its executed result or its proposal).
     */
    static UUID resolveRecordId(
            String content,
            List<ChatMessage> priorMessages,
            String appKind,
            String entity
    ) {
        if (content != null) {
            Matcher explicit = RECORD_ID_PATTERN.matcher(content);
            if (explicit.find()) {
                UUID parsed = asUuid(explicit.group());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        if (priorMessages == null || priorMessages.isEmpty()) {
            return null;
        }
        for (int i = priorMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = priorMessages.get(i);
            if (message == null || message.getAction() == null
                    || !"assistant".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            Map<String, Object> action = message.getAction();
            Object kind = action.get("kind");
            if (!(kind instanceof String actionKind) || !actionKind.startsWith("bizapp.record.")) {
                continue;
            }
            if (appKind != null && action.get("appKind") instanceof String cardKind
                    && !appKind.equalsIgnoreCase(cardKind)) {
                continue;
            }
            if (entity != null && action.get("entity") instanceof String cardEntity
                    && !entity.equalsIgnoreCase(cardEntity)) {
                continue;
            }
            UUID fromCard = asUuid(action.get("recordId"));
            if (fromCard == null && action.get("result") instanceof Map<?, ?> result) {
                fromCard = asUuid(result.get("recordId"));
            }
            if (fromCard != null) {
                return fromCard;
            }
        }
        return null;
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Slot value resolution for record actions: deterministic (regex) extraction
     * first as a cheap in-JVM base, then LLM-assisted extraction when enabled.
     * LLM values win on conflicts (the model sees the whole conversation);
     * regex results backfill only the keys the LLM omitted. Both paths are
     * strictly whitelisted against the manifest's field contract.
     */
    private Map<String, Object> resolveRecordFields(
            List<ChatMessage> priorMessages,
            String content,
            EntityDetail targetEntity,
            String appName,
            String locale
    ) {
        String accumulatedText = buildAccumulatedUserText(priorMessages, content);
        Map<String, Object> fields = new LinkedHashMap<>(extractFieldsFromPrompt(accumulatedText, targetEntity));
        // General asks with zero deterministic signal keep the zero-LLM
        // prompt-for-details path; the agent only runs when there is at least
        // one anchor key to complete.
        if (slotAgent != null && slotAgent.llmEnabled() && !fields.isEmpty()) {
            Map<String, Object> llmSlots = slotAgent.extractSlots(targetEntity, appName, locale, priorMessages, content);
            llmSlots.forEach(fields::put);
        }
        return fields;
    }

    public static boolean matchesDeterministicGate(String query) {
        return matchesDeterministicGate(query, List.of());
    }

    public static boolean matchesDeterministicGate(String query, Collection<String> dynamicAliases) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = removeDiacritics(query.toLowerCase(Locale.ROOT));
        boolean hasAdmin = ADMIN_VERBS.matcher(normalized).find();
        boolean hasWrite = WRITE_VERBS.matcher(normalized).find();
        boolean hasVocab = GENERIC_APP_VOCAB.matcher(normalized).find();
        boolean hasDetail = DETAIL_KEYWORDS.matcher(normalized).find();
        boolean hasManagement = MANAGEMENT_NEED.matcher(normalized).find();
        boolean hasRequest = REQUEST_VERBS.matcher(normalized).find();
        if (hasAdmin || hasWrite || hasVocab || hasDetail || hasManagement || hasRequest) {
            return true;
        }
        if (dynamicAliases != null) {
            for (String alias : dynamicAliases) {
                if (alias != null && alias.length() >= 3 && normalized.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String removeDiacritics(String text) {
        if (text == null) {
            return "";
        }
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        String withoutMarks = nfd.replaceAll("\\p{M}", "");
        return withoutMarks.replace('đ', 'd').replace('Đ', 'D');
    }

    /**
     * Chooses an installed app whose manifest covers the user's request by
     * meaning rather than by verbatim alias. Returns null on failure or when no
     * app fits, so callers always fall back to the normal classifier/RAG path.
     */
    private AppSelection selectInstalledApp(
            String content,
            List<InstalledAppSummary> installed,
            Map<String, String> aliasToKind,
            String locale
    ) {
        if (installed == null || installed.isEmpty()) {
            return null;
        }
        try {
            var structuredResponse = aiInferenceApi.completeJson(new AiInferenceApi.StructuredJsonRequest(
                    "User request: " + content,
                    buildAppSelectorPrompt(installed, locale),
                    APP_SELECTOR_JSON_SCHEMA,
                    "vylinh",
                    0.0f,
                    256
            ));
            String json = structuredResponse.jsonOutput();
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            String rawKind = root.path("appKind").asText("");
            if (rawKind == null || rawKind.isBlank() || "null".equalsIgnoreCase(rawKind.trim())) {
                return null;
            }
            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < SELECTOR_CONFIDENCE_THRESHOLD) {
                return null;
            }
            String kind = normalizeSelectedKind(rawKind, installed, aliasToKind);
            if (kind == null) {
                return null;
            }
            String rawEntity = root.path("entity").asText("");
            String entity = rawEntity == null || rawEntity.isBlank() || "null".equalsIgnoreCase(rawEntity.trim())
                    ? null
                    : rawEntity.trim();
            return new AppSelection(kind, entity);
        } catch (Exception ex) {
            log.warn("Semantic app selection failed: {}", ex.getMessage());
            return null;
        }
    }

    private String normalizeSelectedKind(
            String rawKind,
            List<InstalledAppSummary> installed,
            Map<String, String> aliasToKind
    ) {
        String cleaned = rawKind.trim();
        int separator = cleaned.indexOf(':');
        if (separator >= 0) {
            cleaned = cleaned.substring(separator + 1).trim();
        }
        if (cleaned.isBlank()) {
            return null;
        }
        Set<String> installedKinds = installed.stream()
                .map(InstalledAppSummary::kind)
                .filter(Objects::nonNull)
                .map(kind -> kind.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        String resolved = resolveAppKind(cleaned, installedKinds, aliasToKind);
        if (resolved == null) {
            String normalized = removeDiacritics(cleaned.toLowerCase(Locale.ROOT));
            resolved = installed.stream()
                    .map(InstalledAppSummary::kind)
                    .filter(Objects::nonNull)
                    .filter(kind -> removeDiacritics(kind.toLowerCase(Locale.ROOT)).equals(normalized))
                    .findFirst()
                    .orElse(null);
        }
        if (resolved == null) {
            return null;
        }
        final String wanted = resolved;
        return installed.stream()
                .map(InstalledAppSummary::kind)
                .filter(kind -> kind != null && kind.equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(null);
    }

    private String buildAppSelectorPrompt(List<InstalledAppSummary> installed, String locale) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are OpenVie's app router for one workspace.\n");
        sb.append("Choose the installed app that handles the user's request, or null when none does.\n\n");
        sb.append("Installed apps:\n");
        int appCount = 0;
        for (InstalledAppSummary app : installed) {
            if (app == null || app.kind() == null || app.kind().isBlank()) continue;
            if (appCount++ >= SELECTOR_MAX_APPS) break;
            sb.append("- kind: ").append(app.kind()).append("\n");
            String appName = app.name() != null && !app.name().isBlank() ? app.name() : app.kind();
            sb.append("  name: ").append(appName);
            if (app.nameVi() != null && !app.nameVi().isBlank() && !app.nameVi().equalsIgnoreCase(appName)) {
                sb.append(" / ").append(app.nameVi());
            }
            sb.append("\n");
            if (app.description() != null && !app.description().isBlank()) {
                sb.append("  description: ").append(app.description()).append("\n");
            }
            if (app.triggers() != null && !app.triggers().isEmpty()) {
                sb.append("  triggers: ").append(String.join(", ", app.triggers())).append("\n");
            }
            if (app.entityDetails() != null && !app.entityDetails().isEmpty()) {
                sb.append("  entities:\n");
                int entityCount = 0;
                for (EntityDetail entity : app.entityDetails()) {
                    if (entity == null || entity.name() == null || entity.name().isBlank()) continue;
                    if (entityCount++ >= SELECTOR_MAX_ENTITIES_PER_APP) break;
                    sb.append("    - ").append(entity.name());
                    if (entity.label() != null
                            && !entity.label().isBlank()
                            && !entity.label().equalsIgnoreCase(entity.name())) {
                        sb.append(" (").append(entity.label()).append(")");
                    }
                    if (entity.fields() != null && !entity.fields().isEmpty()) {
                        List<String> fieldNames = entity.fields().stream()
                                .filter(Objects::nonNull)
                                .map(FieldDetail::name)
                                .filter(name -> name != null && !name.isBlank())
                                .limit(SELECTOR_MAX_FIELDS_PER_ENTITY)
                                .toList();
                        if (!fieldNames.isEmpty()) {
                            sb.append(": ").append(String.join(", ", fieldNames));
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        sb.append("\nRules:\n");
        sb.append("- Match by meaning, not only exact words. Use app name, description, triggers, entities and fields.\n");
        sb.append("- Choose an app only when the user wants to perform an action or read data in that app's business domain.\n");
        sb.append("- Return appKind=null for document questions, policy/procedure questions, chit-chat, or when no listed app covers the request.\n");
        sb.append("- Never invent a kind. Use one of the exact kind strings above.\n");
        sb.append("- Return the most specific entity name when the request clearly targets one; otherwise null.\n");
        sb.append("- Do not include a 'kind:' prefix in appKind.\n");
        sb.append("Locale: ").append(locale != null ? locale : "vi").append("\n\n");
        sb.append("Output JSON only: {\"appKind\":\"kind or null\",\"entity\":\"entity or null\",\"confidence\":0.0-1.0}\n");
        return sb.toString();
    }

    private static boolean looksOperational(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return REQUEST_VERBS.matcher(normalized).find()
                || READ_VERBS.matcher(normalized).find()
                || WRITE_VERBS.matcher(normalized).find()
                || MANAGEMENT_NEED.matcher(normalized).find();
    }

    private static boolean isChitChat(String normalized) {
        return normalized != null && !normalized.isBlank() && CHIT_CHAT.matcher(normalized).matches();
    }

    private static String localizedAppName(InstalledAppSummary app, String locale) {
        if (app == null) {
            return "";
        }
        boolean vietnamese = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi");
        if (vietnamese && app.nameVi() != null && !app.nameVi().isBlank()) {
            return app.nameVi();
        }
        if (app.name() != null && !app.name().isBlank()) {
            return app.name();
        }
        return app.kind();
    }

    /**
     * Lists the entity's declared fields for a "what should I provide?" follow-up.
     * The reply is built from the live manifest; it never falls through to RAG.
     */
    private static String buildFieldInfoReply(InstalledAppSummary app, EntityDetail entity, String locale) {
        boolean vietnamese = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi");
        String appName = localizedAppName(app, locale);
        List<FieldDetail> fields = entity == null || entity.fields() == null ? List.of() : entity.fields();
        if (fields.isEmpty()) {
            return vietnamese
                    ? "Ứng dụng " + appName + " chưa khai báo trường dữ liệu nào."
                    : "App " + appName + " declares no data fields yet.";
        }
        List<FieldDetail> required = fields.stream().filter(f -> f != null && f.required()).toList();
        List<FieldDetail> optional = fields.stream().filter(f -> f != null && !f.required()).toList();
        StringBuilder sb = new StringBuilder();
        if (vietnamese) {
            sb.append("Để tạo một mục mới trong ").append(appName).append(", bạn cần cung cấp:\n");
            appendFieldLines(sb, required.isEmpty() ? fields : required);
            if (!required.isEmpty() && !optional.isEmpty()) {
                sb.append("\nTùy chọn:\n");
                appendFieldLines(sb, optional);
            }
            sb.append("\nBạn có thể gửi tất cả trong một tin nhắn.");
        } else {
            sb.append("To create a new entry in ").append(appName).append(", provide:\n");
            appendFieldLines(sb, required.isEmpty() ? fields : required);
            if (!required.isEmpty() && !optional.isEmpty()) {
                sb.append("\nOptional:\n");
                appendFieldLines(sb, optional);
            }
            sb.append("\nYou can send everything in one message.");
        }
        return sb.toString();
    }

    private static void appendFieldLines(StringBuilder sb, List<FieldDetail> fields) {
        for (FieldDetail field : fields) {
            if (field == null) continue;
            String label = field.label() != null && !field.label().isBlank() ? field.label() : field.name();
            sb.append("- ").append(label);
            if (field.options() != null && !field.options().isEmpty()
                    && !(field.options().size() == 1 && "default".equalsIgnoreCase(field.options().get(0)))) {
                sb.append(" (").append(String.join(", ", field.options())).append(")");
            }
            sb.append("\n");
        }
    }

    /**
     * True when the turn asks to do something in a business process. This is
     * deliberately domain-agnostic: it only looks at operational verbs, and
     * excludes turns that ask for knowledge. No app or domain names are
     * mentioned here, so new manifests keep working without code changes.
     */
    private static boolean looksLikeOperationalAction(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (PROCEDURE_KEYWORDS.matcher(normalized).find()
                && !MANAGEMENT_NEED.matcher(normalized).find()) {
            return false;
        }
        boolean create = CREATE_VERBS.matcher(normalized).find()
                || WRITE_VERBS.matcher(normalized).find()
                || SUBMIT_VERBS.matcher(normalized).find();
        boolean management = MANAGEMENT_NEED.matcher(normalized).find();
        if (!create && !management) {
            return false;
        }
        if (INFO_REQUEST.matcher(normalized).find() && !create) {
            return false;
        }
        return true;
    }

    /**
     * Last-resort route for an operational request that no installed app can
     * serve. It offers a generic draft app instead of pretending the request
     * was a document question. The user's own message is carried as the draft
     * hint, so the generated app comes from the conversation, not from any
     * hardcoded domain in the router.
     */
    private RoutingResult offerCreateAppForUncoveredAction(
            String content,
            String normalized,
            String locale,
            UUID sessionId,
            String reason
    ) {
        if (!actionsEnabled || !looksLikeOperationalAction(normalized)) {
            return RoutingResult.none();
        }
        boolean vietnamese = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi");
        String reply = vietnamese
                ? "Hiện chưa có ứng dụng nào phù hợp với yêu cầu này. Mình có thể tạo một ứng dụng mới để xử lý quy trình bạn vừa nêu. Bạn có muốn tạo ứng dụng này không?"
                : "No installed app covers this request yet. I can create a new app for the workflow you described. Would you like to create it?";
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "bizapp_action");
        card.put("actionId", UUID.randomUUID().toString());
        card.put("kind", "bizapp.draft");
        card.put("appName", vietnamese ? "Ứng dụng mới" : "New app");
        card.put("status", "PROPOSED");
        card.put("requiresRole", "ORG_OWNER");
        Map<String, Object> payload = new LinkedHashMap<>();
        if (sessionId != null) {
            payload.put("sessionId", sessionId.toString());
        }
        payload.put("hint", content);
        card.put("payload", payload);
        return new RoutingResult(
                Intent.CREATE_APP,
                null,
                null,
                BigDecimal.valueOf(0.8),
                reply,
                reason,
                null,
                card
        );
    }

    private static boolean isAppBoundIntent(Intent intent) {
        return intent == Intent.INSTALL
                || intent == Intent.UNINSTALL
                || intent == Intent.APP_DETAIL
                || intent == Intent.APP_DATA
                || intent == Intent.APP_RECORD_CREATE
                || intent == Intent.APP_RECORD_UPDATE
                || intent == Intent.APP_RECORD_ARCHIVE
                || intent == Intent.APP_RECORD_SUBMIT;
    }

    private static EntityDetail findEntity(String nameOrLabel, List<EntityDetail> entities) {
        if (nameOrLabel == null || nameOrLabel.isBlank() || entities == null) {
            return null;
        }
        String normalized = removeDiacritics(nameOrLabel.trim().toLowerCase(Locale.ROOT));
        for (EntityDetail entity : entities) {
            if (entity == null) continue;
            if (entity.name() != null
                    && removeDiacritics(entity.name().trim().toLowerCase(Locale.ROOT)).equals(normalized)) {
                return entity;
            }
            if (entity.label() != null
                    && removeDiacritics(entity.label().trim().toLowerCase(Locale.ROOT)).equals(normalized)) {
                return entity;
            }
        }
        return null;
    }

    private String buildSystemPrompt(
            List<InstalledAppSummary> installed,
            List<MarketplaceTemplateSummary> catalog,
            String role,
            String locale,
            String content,
            String directKind,
            Map<String, String> aliasToKind,
            Map<String, ActionCapabilities> capabilities
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are OpenVie's Conversational BizApp intent classifier.\n");
        sb.append("Classify the user prompt into exactly ONE intent:\n");
        sb.append("- LIST_APPS: user wants to view or list installed apps\n");
        sb.append("- APP_DETAIL: user wants details about a specific app manifest or configuration\n");
        sb.append("- APP_DATA: user asks about records or domain data inside an app\n");
        sb.append("- INSTALL: user wants to install an app from the catalog\n");
        sb.append("- UNINSTALL: user wants to uninstall/remove an installed app\n");
        sb.append("- SUGGEST: user asks for app suggestions/recommendations\n");
        sb.append("- CREATE_APP: user explicitly wants to build a new app, or asks how to manage/track a new domain that no installed or marketplace app covers. Propose the app and its key entities/fields in the reply and ask whether to create it.\n");
        sb.append("- APP_RECORD_CREATE: user wants to create/add/insert a new record/data into an app\n");
        sb.append("- APP_RECORD_UPDATE: user wants to update/edit an existing record in an app\n");
        sb.append("- APP_RECORD_ARCHIVE: user wants to delete/archive a record in an app\n");
        sb.append("- APP_RECORD_SUBMIT: user wants to submit a record for approval\n");
        sb.append("- NONE: document questions, guidelines/policy questions, or chit-chat unrelated to any business operation\n\n");

        sb.append("Installed Apps: ");
        sb.append(installed.stream().map(InstalledAppSummary::kind).toList()).append("\n");
        sb.append("Marketplace Catalog: ");
        sb.append(catalog.stream().map(MarketplaceTemplateSummary::kind).toList()).append("\n");
        sb.append("Caller Role: ").append(role != null ? role : "MEMBER").append("\n");
        sb.append("Locale: ").append(locale != null ? locale : "vi").append("\n");
        appendAllowedActions(sb, installed, role, capabilities);

        buildAppHint(sb, content, directKind, installed, catalog, aliasToKind);

        sb.append("\nGuardrails:\n");
        sb.append("- NEVER return INSTALL for an app already listed under Installed Apps, and NEVER return UNINSTALL for an app that is NOT in Installed Apps. For an installed app, use the matching app/record intent for its data operations.\n");
        sb.append("- Polite capability requests that match an installed app's domain ARE that app's record/data intent, not general conversation.\n");
        sb.append("- If an installed app covers the user's operational request, use its record/data intent; never answer an operational request as a general document question.\n");
        sb.append("- Return NONE only for document questions, procedures/policies (\"cách\", \"quy trình\", \"làm sao\"...), or chit-chat unrelated to every business operation.\n");
        sb.append("- An app reachable only through its trigger phrase still counts as the app's kind when two kind names collide.\n\n");
        sb.append("Output JSON schema:\n");
        sb.append("{\"intent\":\"NONE|LIST_APPS|APP_DETAIL|APP_DATA|INSTALL|UNINSTALL|SUGGEST|CREATE_APP|APP_RECORD_CREATE|APP_RECORD_UPDATE|APP_RECORD_ARCHIVE|APP_RECORD_SUBMIT\",");
        sb.append("\"appKind\":\"kind or null\",");
        sb.append("\"entity\":\"entity name or null\",");
        sb.append("\"fields\":{\"fieldName\":\"value\"},");
        sb.append("\"confidence\":0.0-1.0,");
        sb.append("\"reply\":\"conversational explanation for user in the given locale\",");
        sb.append("\"reason\":\"brief reason\"}\n\n");
        sb.append("Few-shot Examples:\n");
        sb.append("- \"Hiện tại đang có bao nhiêu ứng dụng đang được cài đặt?\" -> {\"intent\":\"LIST_APPS\",\"appKind\":null,\"confidence\":0.98,\"reply\":\"Danh sách ứng dụng\"}\n");
        sb.append("- \"Quy trình xử lý hồ sơ gồm mấy bước?\" -> {\"intent\":\"NONE\",\"appKind\":null,\"confidence\":0.98,\"reply\":\"\"}\n");
        appendDynamicAppExamples(sb, installed, catalog);
        return sb.toString();
    }

    /**
     * Builds few-shot examples from the live manifests instead of naming any
     * app or business domain in code. This keeps the router plug-and-play: a
     * newly installed app becomes an example immediately, without a code change.
     */
    private void appendDynamicAppExamples(
            StringBuilder sb,
            List<InstalledAppSummary> installed,
            List<MarketplaceTemplateSummary> catalog
    ) {
        if (installed != null && !installed.isEmpty()) {
            InstalledAppSummary app = installed.get(0);
            String appName = app.name() != null && !app.name().isBlank() ? app.name() : app.kind();
            sb.append("- \"Gỡ ứng dụng ").append(appName).append(" giúp tôi.\" -> {\"intent\":\"UNINSTALL\",\"appKind\":\"")
                    .append(app.kind()).append("\",\"confidence\":0.95,\"reply\":\"Gỡ ứng dụng ").append(appName).append("\"}\n");
            EntityDetail entity = app.entityDetails() != null
                    ? app.entityDetails().stream().filter(Objects::nonNull).findFirst().orElse(null)
                    : null;
            if (entity != null) {
                String entityLabel = entity.label() != null && !entity.label().isBlank()
                        ? entity.label()
                        : entity.name();
                sb.append("- \"Thêm ").append(entityLabel).append(" mới\" (installed app \"").append(appName)
                        .append("\") -> {\"intent\":\"APP_RECORD_CREATE\",\"appKind\":\"").append(app.kind())
                        .append("\",\"entity\":\"").append(entity.name())
                        .append("\",\"confidence\":0.95,\"reply\":\"Chuẩn bị thêm ").append(entityLabel).append("\"}\n");
            }
        }
        if (catalog != null && !catalog.isEmpty()) {
            MarketplaceTemplateSummary app = catalog.get(0);
            String appName = app.name() != null && !app.name().isBlank() ? app.name() : app.kind();
            sb.append("- \"Cài đặt ứng dụng ").append(appName).append("\" -> {\"intent\":\"INSTALL\",\"appKind\":\"")
                    .append(app.kind()).append("\",\"confidence\":0.98,\"reply\":\"Cài đặt ứng dụng\"}\n");
        }
    }

    /**
     * Per-app action menu for the caller's role. The model must only propose
     * actions listed here; asking for anything else is a role restriction, not
     * a missing feature.
     */
    private void appendAllowedActions(
            StringBuilder sb,
            List<InstalledAppSummary> installed,
            String role,
            Map<String, ActionCapabilities> capabilities
    ) {
        if (capabilities == null || installed == null || installed.isEmpty()) {
            return;
        }
        sb.append("Role permissions:\n");
        for (InstalledAppSummary app : installed) {
            ActionCapabilities caps = capabilities.get(app.kind());
            List<String> allowed = new ArrayList<>();
            if (caps != null) {
                if (caps.create()) allowed.add("create");
                if (caps.read()) allowed.add("read");
                if (caps.update()) allowed.add("update");
                if (caps.archive()) allowed.add("archive");
                if (caps.submit()) allowed.add("submit");
                if (caps.approve()) allowed.add("approve");
            }
            sb.append("- ").append(app.kind()).append(" — Allowed actions for caller ")
                    .append(role != null ? role : "MEMBER").append(": ")
                    .append(allowed.isEmpty() ? "none" : String.join(", ", allowed)).append("\n");
        }
        sb.append("- Never propose an action that is not listed for that app. If the user asks for one, explain that their role is not allowed to do it in this app.\n");
    }

    /**
     * Emits the deterministic trigger match as a ranked hint for the model.
     * The alias resolution already proved the app intent at zero cost — sharing
     * it prevents small models from re-deriving appKind from prose. The hint
     * explicitly preserves information-question semantics so procedure/geometry
     * questions are not converted into actions (the deterministic
     * PROCEDURE_KEYWORDS gate also stops those turns upstream).
     */
    private void buildAppHint(
            StringBuilder sb,
            String content,
            String directKind,
            List<InstalledAppSummary> installed,
            List<MarketplaceTemplateSummary> catalog,
            Map<String, String> aliasToKind
    ) {
        if (directKind == null) {
            return;
        }
        String lower = content == null ? null : content.trim().toLowerCase(Locale.ROOT);
        String normalized = removeDiacritics(lower);
        String matchedTrigger = matchedTriggerAlias(normalized, directKind, aliasToKind);
        String appName = null;
        String appDescription = null;
        boolean installedApp = false;
        for (InstalledAppSummary i : installed) {
            if (i.kind().equalsIgnoreCase(directKind)) {
                appName = i.name();
                appDescription = i.description();
                installedApp = true;
                break;
            }
        }
        if (!installedApp) {
            for (MarketplaceTemplateSummary c : catalog) {
                if (c.kind().equalsIgnoreCase(directKind)) {
                    appName = c.name();
                    appDescription = c.description();
                    break;
                }
            }
        }
        sb.append("\nDeterministic trigger match (HIGH TRUST): the user message matches ")
          .append(installedApp ? "installed app" : "catalog app").append(" '").append(directKind).append("'");
        if (appName != null) {
            sb.append(" (").append(appName).append(appDescription != null && !appDescription.isBlank() ? " — " + appDescription : "").append(")");
        }
        if (matchedTrigger != null) {
            sb.append(" via manifest trigger \"").append(matchedTrigger).append("\"");
        }
        sb.append(".\nIf the statement is an action request about it, choose the corresponding record/data intent and set appKind='")
          .append(directKind).append("'; if it is an information question or a procedure, choose NONE/APP_DATA instead.\n");
    }

    private String matchedTriggerAlias(String normalized, String directKind, Map<String, String> aliasToKind) {
        if (normalized == null) return null;
        String best = null;
        for (Map.Entry<String, String> e : aliasToKind.entrySet()) {
            String alias = e.getKey();
            if (alias == null || alias.length() < 3 || !directKind.equalsIgnoreCase(e.getValue())) continue;
            String aliasNorm = removeDiacritics(alias.toLowerCase(Locale.ROOT));
            if (normalized.contains(aliasNorm) || normalized.contains(alias)) {
                if (best == null || alias.length() > best.length()) {
                    best = alias;
                }
            }
        }
        return best;
    }

    /**
     * True when the content contains a manifest-declared conversational trigger
     * of the resolved app kind (as opposed to matching the raw kind name).
     * Optional cheap O(alias-map) scan used by the deterministic fast path.
     */
    public static boolean containsAppActionTrigger(
            String normalizedContent, String kind, Map<String, String> aliasToKind
    ) {
        if (normalizedContent == null || kind == null || aliasToKind == null) return false;
        for (Map.Entry<String, String> e : aliasToKind.entrySet()) {
            String alias = e.getKey();
            if (alias == null || alias.length() < 3 || !kind.equalsIgnoreCase(e.getValue())) continue;
            String aliasNorm = removeDiacritics(alias.toLowerCase(Locale.ROOT));
            if (normalizedContent.contains(aliasNorm) || normalizedContent.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    /** Index/information questions ("X là gì?", "ở đâu?", "khi nào?" — true) keep
     *  RAG/detail semantics instead of being converted into app actions.
     *  Pattern matches the DIACRITICS-STRIPPED normalized form only. */
    public static boolean isInformationalQuestion(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        return Pattern.compile(
                "(?i)\\b(la gi|la sao|nghia la gi|the nao|nhu the nao|lam sao|"
                + "o dau|o cho nao|vi tri nao|khi nao|gio nao|ngay nao|vi sao|tai sao|"
                + "bao nhieu|may buoc|what\\s+is|what's|what\\s+are|"
                + "how\\s+(?:does|do|much|many)|when\\s+is|where\\s+is|explain|meaning)\\b"
        ).matcher(normalized).find();
    }

    public static String resolveAppKind(String raw, Collection<String> knownKinds) {
        return resolveAppKind(raw, knownKinds, Map.of());
    }

    public static String resolveAppKind(String raw, Collection<String> knownKinds, Map<String, String> aliasToKind) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (knownKinds != null && knownKinds.contains(lower)) {
            return lower;
        }
        if (aliasToKind != null && aliasToKind.containsKey(lower)) {
            return aliasToKind.get(lower);
        }
        String normalized = removeDiacritics(lower);
        if (aliasToKind != null && aliasToKind.containsKey(normalized)) {
            return aliasToKind.get(normalized);
        }
        if (aliasToKind != null) {
            for (Map.Entry<String, String> entry : aliasToKind.entrySet()) {
                String alias = entry.getKey();
                if (alias != null && alias.length() >= 3 && (lower.contains(alias) || normalized.contains(alias))) {
                    return entry.getValue();
                }
            }
        }
        if (knownKinds != null) {
            for (String k : knownKinds) {
                if (lower.contains(k) || normalized.contains(k)) {
                    return k;
                }
            }
        }
        return null;
    }

    public static Map<String, String> buildAliasToKindMap(
            List<InstalledAppSummary> installed,
            List<MarketplaceTemplateSummary> catalog
    ) {
        Map<String, String> map = new LinkedHashMap<>();
        if (installed != null) {
            for (InstalledAppSummary i : installed) {
                registerAliases(map, i.kind(), i.name(), i.entityDetails(), i.triggers());
                if (i.nameVi() != null && !i.nameVi().isBlank() && !i.nameVi().equalsIgnoreCase(i.name())) {
                    registerAliases(map, i.kind(), i.nameVi(), i.entityDetails(), i.triggers());
                }
            }
        }
        if (catalog != null) {
            for (MarketplaceTemplateSummary c : catalog) {
                registerAliases(map, c.kind(), c.name(), c.entityDetails(), c.triggers());
            }
        }
        return map;
    }

    private static void registerAliases(
            Map<String, String> map,
            String kind,
            String name,
            List<EntityDetail> entities,
            List<String> triggers
    ) {
        if (kind == null || kind.isBlank()) return;
        String kLower = kind.toLowerCase(Locale.ROOT);
        map.put(kLower, kLower);
        map.put(removeDiacritics(kLower), kLower);
        if (name != null && !name.isBlank()) {
            String nLower = name.toLowerCase(Locale.ROOT);
            map.put(nLower, kLower);
            map.put(removeDiacritics(nLower), kLower);
        }
        // Manifest-declared conversational triggers (true plug-and-play contract)
        if (triggers != null) {
            for (String trig : triggers) {
                if (trig != null && !trig.isBlank()) {
                    String tLower = trig.toLowerCase(Locale.ROOT);
                    map.put(tLower, kLower);
                    map.put(removeDiacritics(tLower), kLower);
                }
            }
        }
        if (entities != null) {
            for (EntityDetail e : entities) {
                if (e.name() != null && !e.name().isBlank()) {
                    String eLower = e.name().toLowerCase(Locale.ROOT);
                    map.put(eLower, kLower);
                    map.put(removeDiacritics(eLower), kLower);
                }
                if (e.label() != null && !e.label().isBlank()) {
                    String lLower = e.label().toLowerCase(Locale.ROOT);
                    map.put(lLower, kLower);
                    map.put(removeDiacritics(lLower), kLower);
                }
                if (e.fields() != null) {
                    for (FieldDetail f : e.fields()) {
                        if (f.optionAliases() != null) {
                            for (List<String> optAliases : f.optionAliases().values()) {
                                if (optAliases != null) {
                                    for (String a : optAliases) {
                                        if (a != null && a.length() >= 3) {
                                            map.put(a.toLowerCase(Locale.ROOT), kLower);
                                            map.put(removeDiacritics(a.toLowerCase(Locale.ROOT)), kLower);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static EntityDetail resolveEntity(String text, List<EntityDetail> entities) {
        if (entities == null || entities.isEmpty()) return null;
        if (entities.size() == 1) return entities.get(0);
        if (text == null || text.isBlank()) return entities.get(0);
        String lower = text.toLowerCase(Locale.ROOT);
        String norm = removeDiacritics(lower);
        for (EntityDetail e : entities) {
            if (lower.contains(e.name().toLowerCase(Locale.ROOT))) return e;
            if (e.label() != null) {
                if (lower.contains(e.label().toLowerCase(Locale.ROOT))) return e;
                if (norm.contains(removeDiacritics(e.label().toLowerCase(Locale.ROOT)))) return e;
            }
        }
        return entities.get(0);
    }

    public static List<Map<String, String>> validateRequiredFields(
            Map<String, Object> fields,
            List<FieldDetail> declaredFields
    ) {
        List<Map<String, String>> invalid = new ArrayList<>();
        if (declaredFields == null) return invalid;
        for (FieldDetail f : declaredFields) {
            Object val = fields != null ? fields.get(f.name()) : null;
            if (f.required()) {
                if (val == null || String.valueOf(val).isBlank()) {
                    String optHelp = (f.options() != null && !f.options().isEmpty())
                            ? " (chọn: " + String.join(", ", f.options()) + ")"
                            : "";
                    invalid.add(Map.of(
                            "field", f.name(),
                            "code", "REQUIRED",
                            "message", "Trường " + (f.label() != null ? f.label() : f.name()) + optHelp + " là bắt buộc"
                    ));
                    continue;
                }
            }
            // If enum value is present, verify it matches allowed options
            if (val != null && f.options() != null && !f.options().isEmpty()) {
                String strVal = String.valueOf(val).trim();
                boolean matches = f.options().stream().anyMatch(opt -> opt.equalsIgnoreCase(strVal));
                if (!matches) {
                    invalid.add(Map.of(
                            "field", f.name(),
                            "code", "INVALID_ENUM",
                            "message", "Giá trị '" + strVal + "' không có trong danh mục của trường "
                                    + (f.label() != null ? f.label() : f.name()) + ". Vui lòng chọn một trong các mục: "
                                    + String.join(", ", f.options())
                    ));
                }
            }
        }
        return invalid;
    }

    /** Lowercase Vietnamese forms of address (and Latin titles) in front of a name. */
    private static final Pattern HONORIFIC = Pattern.compile(
            "^(?:(?:anh|chị|chi|em|ông|ong|bà|cô|chú|bác)|(?i:mr|mrs|ms|miss))\\.?\\s+(?=\\S)");

    /**
     * Drops a leading form of address ("anh Trấn Thành" -> "Trấn Thành"). Vietnamese
     * forms are matched in lowercase only, so a capitalized given name such as
     * "Anh Thư" is left intact; a bare "anh" with no name after it is left alone too.
     */
    public static String stripHonorific(String name) {
        return name == null ? "" : HONORIFIC.matcher(name.trim()).replaceFirst("").trim();
    }

    public static String cleanNameValue(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        cleaned = cleaned.replaceAll("(?i)^(?:kh[aá]ch\\s+(?:h[aà]ng\\s+)?(?:l[aà]|t[eê]n)?|t[eê]n\\s+(?:kh[aá]ch\\s+)?(?:l[aà])?|l[aà]|ng[uư][oờ]i)\\s+", "").trim();
        cleaned = stripHonorific(cleaned);
        cleaned = cleaned.replaceAll("[?.,!;:~]+$", "").trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        String norm = removeDiacritics(lower);
        if (norm.contains("giup") || norm.contains("khong") || norm.contains("duoc") || norm.contains("nao") || norm.contains("chua") || norm.contains("co the")) {
            return "";
        }
        return cleaned;
    }

    public static String mapEnumOption(String input, FieldDetail field) {
        if (input == null || field == null || field.options() == null || field.options().isEmpty()) return null;
        String candidate = input.trim();
        for (String opt : field.options()) {
            if (opt.equalsIgnoreCase(candidate)) {
                return opt;
            }
        }
        String candidateNorm = removeDiacritics(candidate.toLowerCase(Locale.ROOT));
        for (String opt : field.options()) {
            String optNorm = removeDiacritics(opt.toLowerCase(Locale.ROOT));
            if (optNorm.equals(candidateNorm) || candidateNorm.contains(optNorm) || optNorm.contains(candidateNorm)) {
                return opt;
            }
        }
        // Manifest-driven option aliases (from field's optionLabels)
        if (field.optionAliases() != null && !field.optionAliases().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : field.optionAliases().entrySet()) {
                String optKey = entry.getKey();
                List<String> aliases = entry.getValue();
                if (aliases != null) {
                    for (String alias : aliases) {
                        String aliasNorm = removeDiacritics(alias.toLowerCase(Locale.ROOT));
                        if (aliasNorm.equals(candidateNorm) || candidateNorm.contains(aliasNorm)) {
                            return optKey;
                        }
                    }
                }
            }
        }
        return null;
    }

    public static String mapEnumOption(String input, List<String> options) {
        if (input == null || options == null || options.isEmpty()) return null;
        String candidate = input.trim();
        for (String opt : options) {
            if (opt.equalsIgnoreCase(candidate)) {
                return opt;
            }
        }
        String candidateNorm = removeDiacritics(candidate.toLowerCase(Locale.ROOT));
        for (String opt : options) {
            String optNorm = removeDiacritics(opt.toLowerCase(Locale.ROOT));
            if (optNorm.equals(candidateNorm) || candidateNorm.contains(optNorm) || optNorm.contains(candidateNorm)) {
                return opt;
            }
        }
        return null;
    }

    public static Map<String, String> buildFieldLabels(List<FieldDetail> declaredFields) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (declaredFields != null) {
            for (FieldDetail f : declaredFields) {
                labels.put(f.name(), f.label() != null ? f.label() : f.name());
            }
        }
        return labels;
    }

    public static String resolveDateString(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        String norm = removeDiacritics(lower);
        java.time.LocalDate today = java.time.LocalDate.now();

        if (norm.contains("hom nay") || norm.contains("ngay nay") || norm.contains("chieu nay")
                || norm.contains("sang nay") || norm.contains("toi nay") || norm.contains("today")) {
            return today.toString();
        }
        if (norm.contains("ngay mai") || norm.contains("mai") || norm.contains("tomorrow")) {
            return today.plusDays(1).toString();
        }
        if (norm.contains("ngay kia") || norm.contains("kia") || norm.contains("mot")) {
            return today.plusDays(2).toString();
        }
        if (norm.contains("hom qua") || norm.contains("yesterday")) {
            return today.minusDays(1).toString();
        }

        int currentDow = today.getDayOfWeek().getValue(); // Monday=1, ..., Sunday=7
        java.time.LocalDate mondayThisWeek = today.minusDays(currentDow - 1);

        if (norm.contains("cuoi tuan sau") || norm.contains("next weekend")) {
            return mondayThisWeek.plusDays(12).toString(); // Saturday next week
        }
        if (norm.contains("cuoi tuan nay") || norm.contains("cuoi tuan") || norm.contains("this weekend") || norm.contains("weekend")) {
            return mondayThisWeek.plusDays(5).toString(); // Saturday this week
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
                java.time.LocalDate mondayNextWeek = mondayThisWeek.plusDays(7);
                return mondayNextWeek.plusDays(targetDay - 1).toString();
            } else if (isThisWeek) {
                return mondayThisWeek.plusDays(targetDay - 1).toString();
            } else {
                if (targetDay > currentDow) {
                    return mondayThisWeek.plusDays(targetDay - 1).toString();
                } else {
                    java.time.LocalDate mondayNextWeek = mondayThisWeek.plusDays(7);
                    return mondayNextWeek.plusDays(targetDay - 1).toString();
                }
            }
        }

        Matcher m = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?").matcher(text.trim());
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = m.group(3) != null
                        ? (m.group(3).length() == 2 ? 2000 + Integer.parseInt(m.group(3)) : Integer.parseInt(m.group(3)))
                        : today.getYear();
                return java.time.LocalDate.of(year, month, day).toString();
            } catch (Exception ignored) {}
        }

        try {
            return java.time.LocalDate.parse(text.trim()).toString();
        } catch (Exception ignored) {}

        return null;
    }

    public static String resolveTimeString(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.trim().toLowerCase(Locale.ROOT);
        boolean isPm = lower.contains("pm") || lower.contains("chieu") || lower.contains("chiều") || lower.contains("toi") || lower.contains("tối");
        boolean isAm = lower.contains("am") || lower.contains("sang") || lower.contains("sáng");

        Matcher m1 = Pattern.compile("(\\d{1,2})\\s*h\\s*(\\d{0,2})").matcher(text);
        if (m1.find()) {
            int hour = Integer.parseInt(m1.group(1));
            int min = (m1.group(2) != null && !m1.group(2).isBlank()) ? Integer.parseInt(m1.group(2)) : 0;
            if (isPm && hour < 12) hour += 12;
            else if (isAm && hour == 12) hour = 0;
            return String.format("%02d:%02d", hour, min);
        }

        Matcher m2 = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
        if (m2.find()) {
            int hour = Integer.parseInt(m2.group(1));
            int min = Integer.parseInt(m2.group(2));
            if (isPm && hour < 12) hour += 12;
            else if (isAm && hour == 12) hour = 0;
            return String.format("%02d:%02d", hour, min);
        }

        Matcher m3 = Pattern.compile("(\\d{1,2})\\s*(?:am|pm|chi[eề]u|t[oố]i|s[aá]ng)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m3.find()) {
            int hour = Integer.parseInt(m3.group(1));
            if (isPm && hour < 12) hour += 12;
            else if (isAm && hour == 12) hour = 0;
            return String.format("%02d:00", hour);
        }

        return null;
    }

    /**
     * Field-role detection is manifest-driven: it looks at the declared name,
     * label, type and format, never at a specific app's field names. Any app
     * can therefore plug in a "Tên người gửi", "Email liên hệ" or
     * "Ngày bắt đầu" field without router changes.
     */
    private static boolean fieldNameOrLabelContains(FieldDetail field, String... needles) {
        if (field == null) {
            return false;
        }
        String rawName = field.name() == null ? "" : field.name()
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[_-]+", " ");
        String haystack = removeDiacritics((rawName + " " + (field.label() == null ? "" : field.label())).toLowerCase(Locale.ROOT));
        for (String needle : needles) {
            if (needle == null || needle.isBlank()) continue;
            String normalizedNeedle = removeDiacritics(needle.toLowerCase(Locale.ROOT)).trim();
            if (normalizedNeedle.isEmpty()) continue;
            if (Pattern.compile("\\b" + Pattern.quote(normalizedNeedle) + "\\b").matcher(haystack).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeNameField(FieldDetail field) {
        return field != null && ("name".equalsIgnoreCase(field.name()) || fieldNameOrLabelContains(field, "name", "ten"));
    }

    private static boolean looksLikeEmailField(FieldDetail field) {
        return field != null && ("email".equalsIgnoreCase(field.format()) || fieldNameOrLabelContains(field, "email", "mail"));
    }

    private static boolean looksLikePhoneField(FieldDetail field) {
        return field != null && ("phone".equalsIgnoreCase(field.format())
                || fieldNameOrLabelContains(field, "phone", "sdt", "dien thoai", "mobile"));
    }

    private static boolean looksLikeDateField(FieldDetail field) {
        return field != null && ("date".equalsIgnoreCase(field.type())
                || fieldNameOrLabelContains(field, "date", "ngay", "thoi gian"));
    }

    private static boolean looksLikeTimeField(FieldDetail field) {
        return field != null && ("time".equalsIgnoreCase(field.type())
                || fieldNameOrLabelContains(field, "time", "gio"));
    }

    private static boolean looksLikeNotesField(FieldDetail field) {
        return field != null && fieldNameOrLabelContains(field, "note", "ghi chu", "mo ta");
    }

    private static boolean hasValueForFieldRole(
            Map<String, Object> extracted,
            EntityDetail entity,
            Predicate<FieldDetail> predicate
    ) {
        if (extracted == null || entity == null || entity.fields() == null) {
            return false;
        }
        return entity.fields().stream()
                .filter(Objects::nonNull)
                .filter(predicate)
                .anyMatch(field -> extracted.containsKey(field.name()));
    }

    private static void putFirstMatchingField(
            Map<String, Object> extracted,
            EntityDetail entity,
            Object value,
            Predicate<FieldDetail> predicate
    ) {
        if (extracted == null || entity == null || entity.fields() == null) {
            return;
        }
        entity.fields().stream()
                .filter(Objects::nonNull)
                .filter(predicate)
                .findFirst()
                .ifPresent(field -> extracted.putIfAbsent(field.name(), value));
    }

    private static boolean isUsableName(String name) {
        return name != null
                && !name.isBlank()
                && !name.equalsIgnoreCase("moi")
                && !name.equalsIgnoreCase("mới");
    }

    /**
     * Builds a name-capture pattern from the entity's own name-field labels
     * instead of hardcoding role words. A manifest declaring
     * "Tên nhân viên" gets a "nhân viên" trigger automatically.
     */
    private static Pattern buildEntityNamePattern(EntityDetail entity) {
        if (entity == null || entity.fields() == null) {
            return null;
        }
        Set<String> triggers = new LinkedHashSet<>();
        for (FieldDetail field : entity.fields()) {
            if (!looksLikeNameField(field)) continue;
            String label = field.label() == null ? "" : field.label().trim();
            if (!label.isBlank()) {
                triggers.add(label);
                String stripped = label.replaceAll("(?i)^(họ\\s+tên|ho\\s+ten|tên|ten|full\\s+name|name)\\s+", "").trim();
                if (!stripped.isBlank() && !stripped.equalsIgnoreCase(label)) {
                    triggers.add(stripped);
                }
            }
            String rawName = field.name() == null ? "" : field.name()
                    .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                    .replaceAll("[_-]+", " ")
                    .trim();
            if (!rawName.isBlank() && !rawName.equalsIgnoreCase("name")) {
                triggers.add(rawName);
                String withoutNameSuffix = rawName.replaceAll("(?i)\\s+(full\\s+)?name$", "").trim();
                if (!withoutNameSuffix.isBlank() && !withoutNameSuffix.equalsIgnoreCase(rawName)) {
                    triggers.add(withoutNameSuffix);
                }
            }
        }
        if (triggers.isEmpty()) {
            return null;
        }
        String alternation = triggers.stream()
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        return Pattern.compile(
                "(?i)\\b(?:" + alternation + ")\\s+"
                        + "(?:l[aà]\\s+|t[eê]n\\s+|name\\s+|is\\s+)?"
                        + "(?-i:\\p{Ll}+\\s+)?"
                        + "([\\p{Lu}][^\\d,;:/\\n]*?)(?=[,.;\\n]|$)",
                Pattern.UNICODE_CHARACTER_CLASS
        );
    }

    public static Map<String, Object> extractFieldsFromPrompt(String prompt, EntityDetail entity) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (prompt == null || prompt.isBlank() || entity == null || entity.fields() == null) {
            return extracted;
        }

        // 1. Generic key-value extraction: e.g. "Field là value", "Trường: giá trị"
        Matcher kvMatcher = Pattern.compile("(?i)\\b([a-zA-Z0-9_]+|[\\p{L}\\s]{2,20}?)\\s*(?:l[aà]|:|=)\\s*([^,;\\n]+)", Pattern.UNICODE_CHARACTER_CLASS).matcher(prompt);
        while (kvMatcher.find()) {
            String rawKey = kvMatcher.group(1).trim().toLowerCase(Locale.ROOT);
            String rawVal = kvMatcher.group(2).trim();
            if (rawVal.isBlank() || rawKey.equalsIgnoreCase("toi") || rawKey.equalsIgnoreCase("ban") || rawKey.equalsIgnoreCase("la")) continue;

            for (FieldDetail f : entity.fields()) {
                String fName = f.name().toLowerCase(Locale.ROOT);
                String fLabel = f.label() != null ? f.label().toLowerCase(Locale.ROOT) : "";
                String fLabelNorm = removeDiacritics(fLabel);
                String rawKeyNorm = removeDiacritics(rawKey);

                if (fName.equals(rawKey) || fName.equals(rawKeyNorm) || fLabel.equals(rawKey) || fLabelNorm.equals(rawKeyNorm)
                        || rawKey.endsWith(fName) || rawKeyNorm.endsWith(fName)
                        || (fLabel.length() >= 3 && rawKey.contains(fLabel))) {
                    if (f.options() != null && !f.options().isEmpty()) {
                        String mapped = mapEnumOption(rawVal, f);
                        extracted.put(f.name(), mapped != null ? mapped : rawVal);
                    } else if (looksLikeNameField(f)) {
                        extracted.put(f.name(), cleanNameValue(rawVal));
                    } else {
                        extracted.put(f.name(), rawVal);
                    }
                }
            }
        }

        // 2. Email extraction
        Matcher emailMatcher = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").matcher(prompt);
        if (emailMatcher.find() && !hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeEmailField)) {
            String email = emailMatcher.group();
            putFirstMatchingField(extracted, entity, email, BizAppIntentRouter::looksLikeEmailField);
            extracted.putIfAbsent("email", email);
        }

        // 3. Phone extraction
        Matcher phoneMatcher = Pattern.compile("(?<!\\d)(0\\d{8,10})(?!\\d)").matcher(prompt);
        if (phoneMatcher.find() && !hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikePhoneField)) {
            String phone = phoneMatcher.group(1);
            putFirstMatchingField(extracted, entity, phone, BizAppIntentRouter::looksLikePhoneField);
            extracted.putIfAbsent("phone", phone);
        }

        // 4. Date and time extraction
        Matcher dateMatcher = Pattern.compile("(?i)\\b((?:h[oô]m\\s+nay|ng[aà]y\\s+nay|(?:chi[eề]u|s[aá]ng|t[oố]i)\\s+nay|ng[aà]y\\s+mai|ng[aà]y\\s+kia|h[oô]m\\s+qua|today|tomorrow|yesterday|cu[oố]i\\s+tu[aầ]n(?:\s+(?:sau|t[oớ]i|n[aà]y))?|(?:next|this)\s+weekend|th[uứ]\s+[2-7]|th[uứ]\s+(?:hai|ba|t[uư]|n[aă]m|s[aá]u|b[aả]y)|ch[uủ]\s+nh[aậ]t|\\bcn\\b|(?:next|this)?\\s*(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))(?:\s+(?:tu[aầ]n\s+(?:sau|t[oớ]i|n[aà]y)|next|this))?|\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?)").matcher(prompt);
        String foundIsoDate = null;
        if (dateMatcher.find()) {
            String dt = dateMatcher.group(1).trim();
            foundIsoDate = resolveDateString(dt);
            if (foundIsoDate == null) {
                foundIsoDate = dt;
            }
        }

        Matcher timeMatcher = Pattern.compile("(?i)\\b(?:l[uú]c|v[aà]o|at)?\\s*(\\d{1,2}\\s*h\\s*\\d{0,2}(?:\\s*(?:chi[eề]u|t[oố]i|s[aá]ng))?|\\d{1,2}:\\d{2}\\s*(?:am|pm|chi[eề]u|t[oố]i|s[aá]ng)?|\\d{1,2}\\s*(?:am|pm|chi[eề]u|t[oố]i|s[aá]ng))").matcher(prompt);
        String foundFormattedTime = null;
        if (timeMatcher.find()) {
            foundFormattedTime = resolveTimeString(timeMatcher.group(1).trim());
        }

        if (foundIsoDate != null && !hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeDateField)) {
            String dateValue = foundIsoDate;
            putFirstMatchingField(extracted, entity, dateValue, BizAppIntentRouter::looksLikeDateField);
            extracted.putIfAbsent("date", dateValue);
        }

        if (foundFormattedTime != null && !hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeTimeField)) {
            String timeValue = foundFormattedTime;
            putFirstMatchingField(extracted, entity, timeValue, BizAppIntentRouter::looksLikeTimeField);
            extracted.putIfAbsent("time", timeValue);
        }

        Matcher notesMatcher = Pattern.compile("(?i)\\b(?:ghi\\s*ch[uú]|notes?|m[oô]\\s*t[aả])\\s*[:\\-\\s]?\\s*([^,;\\n]+)").matcher(prompt);
        if (notesMatcher.find() && !hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeNotesField)) {
            String notes = notesMatcher.group(1).trim();
            putFirstMatchingField(extracted, entity, notes, BizAppIntentRouter::looksLikeNotesField);
            extracted.putIfAbsent("notes", notes);
        }
        // 5. Name extraction
        Matcher nameMatcher = Pattern.compile("(?i)\\b(?:t[eê]n|\\bname)\\s*(?:l[aà]|:|=)?\\s*([^\\d,;:/\\n]+?)(?=[,.;\\n]|$)", Pattern.UNICODE_CHARACTER_CLASS).matcher(prompt);
        if (nameMatcher.find() && !hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeNameField)) {
            String name = cleanNameValue(nameMatcher.group(1));
            if (isUsableName(name)) {
                putFirstMatchingField(extracted, entity, name, BizAppIntentRouter::looksLikeNameField);
                extracted.putIfAbsent("name", name);
            }
        }
        if (!hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeNameField)) {
            Pattern personPattern = buildEntityNamePattern(entity);
            if (personPattern != null) {
                Matcher personMatcher = personPattern.matcher(prompt);
                if (personMatcher.find()) {
                    String name = cleanNameValue(personMatcher.group(1));
                    if (isUsableName(name)) {
                        putFirstMatchingField(extracted, entity, name, BizAppIntentRouter::looksLikeNameField);
                        extracted.putIfAbsent("name", name);
                    }
                }
            }
        }

        // 6. Comma name extraction fallback (e.g. "Hôm nay 13h45, Nguyễn Văn A")
        if (!hasValueForFieldRole(extracted, entity, BizAppIntentRouter::looksLikeNameField)
                && !extracted.containsKey("title")) {
            Matcher commaNameMatcher = Pattern.compile("(?:,|–|-)\\s*([\\p{Lu}][\\p{L}\\s]{1,30}?)(?=[,.;\\n]|$)", Pattern.UNICODE_CHARACTER_CLASS).matcher(prompt);
            while (commaNameMatcher.find()) {
                String val = cleanNameValue(commaNameMatcher.group(1));
                String valLower = val.toLowerCase(Locale.ROOT);
                if (!val.isBlank() && val.length() >= 2 && !valLower.contains("la ") && !valLower.contains("là ")) {
                    putFirstMatchingField(extracted, entity, val, BizAppIntentRouter::looksLikeNameField);
                    extracted.putIfAbsent("name", val);
                    break;
                }
            }
        }

        // 7. Enum options matching directly from prompt
        for (FieldDetail f : entity.fields()) {
            if (f.options() != null && !f.options().isEmpty() && !extracted.containsKey(f.name())) {
                String mapped = mapEnumOption(prompt, f);
                if (mapped != null) {
                    extracted.put(f.name(), mapped);
                }
            }
        }
        Set<String> declared = new HashSet<>();
        for (FieldDetail f : entity.fields()) {
            declared.add(f.name());
        }
        extracted.keySet().removeIf(k -> !declared.contains(k));

        for (FieldDetail f : entity.fields()) {
            if (looksLikeNameField(f) && extracted.containsKey(f.name())) {
                extracted.put(f.name(), cleanNameValue(String.valueOf(extracted.get(f.name()))));
            }
        }

        return extracted;
    }

    public static String computePayloadHash(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "sha256:empty";
        try {
            SortedMap<String, Object> sorted = new TreeMap<>(payload);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                sb.append(entry.getKey()).append("=").append(String.valueOf(entry.getValue())).append(";");
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return "sha256:" + payload.hashCode();
        }
    }

    public static Map<String, Object> buildRecordActionCard(
            String kind,
            String appKind,
            String appName,
            String entity,
            String entityLabel,
            UUID recordId,
            Map<String, Object> fields,
            Map<String, String> fieldLabels,
            String status,
            List<Map<String, String>> invalidList,
            UUID sessionId,
            String payloadHash,
            String requiresRole
    ) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "bizapp_action");
        card.put("actionId", UUID.randomUUID().toString());
        card.put("kind", kind);
        card.put("status", status);
        card.put("appKind", appKind);
        card.put("appName", appName);
        card.put("entity", entity);
        card.put("entityLabel", entityLabel);
        if (recordId != null) {
            card.put("recordId", recordId.toString());
        }
        card.put("fields", fields != null ? fields : Map.of());
        card.put("fieldLabels", fieldLabels != null ? fieldLabels : Map.of());
        if (invalidList != null && !invalidList.isEmpty()) {
            card.put("invalid", invalidList);
        }
        if (payloadHash != null) {
            card.put("payloadHash", payloadHash);
        }
        card.put("requiresRole", requiresRole == null || requiresRole.isBlank() ? "MEMBER" : requiresRole);
        card.put("executedBy", null);
        card.put("executedAt", null);
        card.put("result", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (sessionId != null) {
            payload.put("sessionId", sessionId.toString());
        }
        card.put("payload", payload);
        return card;
    }

    private Map<String, Object> buildActionCard(AppActionOffer offer, String status, UUID sessionId) {
        if (offer == null) return null;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "bizapp_action");
        card.put("actionId", offer.actionId().toString());
        card.put("kind", offer.kind());
        card.put("status", status);
        card.put("appKind", offer.appKind());
        card.put("appName", offer.appName());
        card.put("version", offer.version());
        card.put("description", offer.description());
        card.put("requiresRole", offer.requiresRole());
        card.put("executedBy", null);
        card.put("executedAt", null);
        card.put("result", null);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (offer.templateRef() != null) {
            payload.put("templateRef", offer.templateRef());
        }
        if (sessionId != null) {
            payload.put("sessionId", sessionId.toString());
        }
        card.put("payload", payload);
        return card;
    }

    public static String findRecentAppContext(
            List<ChatMessage> priorMessages,
            Set<String> knownKinds,
            Map<String, String> aliasToKind
    ) {
        if (priorMessages == null || priorMessages.isEmpty()) return null;
        int size = priorMessages.size();
        int start = Math.max(0, size - 6);

        for (int i = size - 1; i >= start; i--) {
            ChatMessage msg = priorMessages.get(i);
            if (msg == null) continue;

            // If previous action card was executed, flow is finished
            if (msg.getAction() != null && "EXECUTED".equalsIgnoreCase(String.valueOf(msg.getAction().get("status")))) {
                return null;
            }

            if (msg.getAction() != null && msg.getAction().get("appKind") != null) {
                String k = String.valueOf(msg.getAction().get("appKind"));
                if (knownKinds != null && knownKinds.contains(k.toLowerCase(Locale.ROOT))) {
                    return k.toLowerCase(Locale.ROOT);
                }
            }

            String text = msg.getContent();
            if (text != null && !text.isBlank()) {
                String kind = resolveAppKind(text, knownKinds, aliasToKind);
                if (kind != null) {
                    return kind;
                }
            }
        }
        return null;
    }

    public static String buildAccumulatedUserText(List<ChatMessage> priorMessages, String currentContent) {
        if (priorMessages == null || priorMessages.isEmpty()) {
            return currentContent != null ? currentContent : "";
        }
        StringBuilder sb = new StringBuilder();
        int size = priorMessages.size();
        int start = Math.max(0, size - 6);
        for (int i = start; i < size; i++) {
            ChatMessage msg = priorMessages.get(i);
            if (msg != null && "user".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null) {
                String norm = removeDiacritics(msg.getContent().toLowerCase(Locale.ROOT));
                if (!isFillerTurn(norm)) {
                    sb.append(msg.getContent()).append(" ");
                }
            }
        }
        if (currentContent != null) {
            sb.append(currentContent);
        }
        return sb.toString().trim();
    }

    public static String buildMissingFieldsPrompt(
            List<Map<String, String>> invalidList,
            Map<String, Object> fields,
            EntityDetail targetEntity,
            String locale,
            String appName
    ) {
        boolean isVi = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi");
        StringBuilder sb = new StringBuilder();
        if (isVi) {
            if (fields != null && !fields.isEmpty()) {
                sb.append("Tôi đã ghi nhận: ");
                List<String> recorded = new ArrayList<>();
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    String label = targetEntity != null ? targetEntity.fieldLabel(entry.getKey()) : entry.getKey();
                    recorded.add(label + ": " + entry.getValue());
                }
                sb.append(String.join(", ", recorded)).append(". ");
            }
            sb.append("Để hoàn tất yêu cầu trong ").append(appName != null ? appName : "ứng dụng");
            sb.append(", vui lòng bổ sung các thông tin sau:\n");

            for (Map<String, String> inv : invalidList) {
                String code = inv.get("code");
                String fName = inv.get("field");
                FieldDetail f = targetEntity != null ? targetEntity.findField(fName) : null;
                String label = f != null && f.label() != null && !f.label().isBlank() ? f.label() : fName;

                if ("INVALID_ENUM".equals(code)) {
                    sb.append("- ").append(inv.get("message")).append("\n");
                } else {
                    sb.append("- **").append(label).append("**: ");
                    if (f != null && f.options() != null && !f.options().isEmpty()) {
                        sb.append("chọn một trong các mục: ").append(String.join(", ", f.options()));
                    } else if (looksLikeDateField(f) || "date".equalsIgnoreCase(fName)) {
                        sb.append("ví dụ: Hôm nay 14h, hoặc ngày mai lúc 9h");
                    } else if (looksLikeEmailField(f) || "email".equalsIgnoreCase(fName)) {
                        sb.append("ví dụ: email@example.com");
                    } else {
                        sb.append("thông tin ").append(label.toLowerCase(Locale.ROOT));
                    }
                    sb.append("\n");
                }
            }
        } else {
            if (fields != null && !fields.isEmpty()) {
                sb.append("I have recorded: ");
                List<String> recorded = new ArrayList<>();
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    String label = targetEntity != null ? targetEntity.fieldLabel(entry.getKey()) : entry.getKey();
                    recorded.add(label + ": " + entry.getValue());
                }
                sb.append(String.join(", ", recorded)).append(". ");
            }
            sb.append("To complete the record in ").append(appName != null ? appName : "app");
            sb.append(", please provide the following details:\n");

            for (Map<String, String> inv : invalidList) {
                String code = inv.get("code");
                String fName = inv.get("field");
                FieldDetail f = targetEntity != null ? targetEntity.findField(fName) : null;
                String label = f != null && f.label() != null && !f.label().isBlank() ? f.label() : fName;

                if ("INVALID_ENUM".equals(code)) {
                    sb.append("- ").append(inv.get("message")).append("\n");
                } else {
                    sb.append("- **").append(label).append("**: ");
                    if (f != null && f.options() != null && !f.options().isEmpty()) {
                        sb.append("options: ").append(String.join(", ", f.options()));
                    } else {
                        sb.append("required value");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }
}
