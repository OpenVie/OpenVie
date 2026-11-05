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
import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppActionOffer;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
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

    private static final Pattern ADMIN_VERBS = Pattern.compile(
            "\\b(cai|cai dat|install|go|go cai dat|uninstall|xoa app|remove app|danh sach app|list apps?|show apps?|xem app|tao app|lap app|them app|create app|build app|compose app|goi y app|suggest apps?|ung dung|marketplace)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WRITE_VERBS = Pattern.compile(
            "\\b(them|tao|lap|nhap|ghi|ghi nhan|luu|luu tru|cap nhat|sua|doi|xoa|go bo|gui duyet|trinh duyet|"
            + "add|create|insert|log|save|update|edit|change|delete|remove|archive|submit|send for approval|"
            + "schedule|book|reserve|set up|set)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GENERIC_APP_VOCAB = Pattern.compile(
            "\\b(app|apps|ung dung|phan mem|ban ghi|record|records|lead|leads|item|items|du lieu|data|thuc the|entity|entities|truong|fields?|cai dat|install|go cai dat|uninstall|marketplace|catalog|danh muc)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PROCEDURE_KEYWORDS = Pattern.compile(
            "\\b(quy trinh|huong dan|chinh sach|cach|cach nao|lam sao|lam the nao|nhu the nao|quy dinh|thu tuc|policy|guideline|procedure|how to)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DETAIL_KEYWORDS = Pattern.compile(
            "\\b(truong|truong nao|fields?|thuoc tinh|thuc the|entities|entity|cau truc|chi tiet|detail|details|thong tin|gom nhung)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FILLER_KEYWORDS = Pattern.compile(
            "\\b(cho\\s+(?:toi\\s+)?(?:ty|ti|chut|da)|doi\\s+(?:toi\\s+)?(?:ty|ti|chut|da)|khoan\\s+da|wait|hold\\s+on|one\\s+moment)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static boolean isFillerTurn(String normalized) {
        if (normalized == null) return false;
        return FILLER_KEYWORDS.matcher(normalized).find();
    }
    private final BizAppRuntimeApi bizAppRuntimeApi;
    private final AiInferenceApi aiInferenceApi;
    private final ObjectMapper objectMapper;

    public BizAppIntentRouter(
            @org.springframework.context.annotation.Lazy BizAppRuntimeApi bizAppRuntimeApi,
            AiInferenceApi aiInferenceApi,
            ObjectMapper objectMapper
    ) {
        this.bizAppRuntimeApi = bizAppRuntimeApi;
        this.aiInferenceApi = aiInferenceApi;
        this.objectMapper = objectMapper;
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
        Map<String, String> aliasToKind = buildAliasToKindMap(installed, catalog);

        Set<String> knownKinds = new HashSet<>();
        installed.forEach(i -> knownKinds.add(i.kind().toLowerCase(Locale.ROOT)));
        catalog.forEach(c -> knownKinds.add(c.kind().toLowerCase(Locale.ROOT)));
        String normalized = removeDiacritics(content.toLowerCase(Locale.ROOT));

        // 1. Resolve app kind from content
        String directKind = resolveAppKind(content, knownKinds, aliasToKind);

        // 2. Multi-turn lookback window: if content alone doesn't have an app kind, check prior messages!
        boolean fromContext = false;
        if (directKind == null && priorMessages != null && !priorMessages.isEmpty()) {
            directKind = findRecentAppContext(priorMessages, knownKinds, aliasToKind);
            if (directKind != null) {
                fromContext = true;
            }
        }

        // Procedure/policy/guideline questions must always go to RAG
        if (PROCEDURE_KEYWORDS.matcher(normalized).find()) {
            return RoutingResult.none();
        }

        boolean inActiveContext = (directKind != null);

        if (!classifyAllTurns && !matchesDeterministicGate(content, aliasToKind.keySet()) && !inActiveContext) {
            return RoutingResult.none();
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

        // Fast deterministic record create match or prompt to install
        if (directKind != null && (WRITE_VERBS.matcher(normalized).find() || fromContext)) {
            final String targetAppKind = directKind;
            var instOpt = installed.stream().filter(i -> i.kind().equalsIgnoreCase(targetAppKind)).findFirst();
            if (instOpt.isPresent()) {
                InstalledAppSummary app = instOpt.get();
                String accumulatedText = buildAccumulatedUserText(priorMessages, content);
                EntityDetail targetEntity = resolveEntity(accumulatedText, app.entityDetails());
                if (targetEntity != null) {
                    Map<String, Object> fields = extractFieldsFromPrompt(accumulatedText, targetEntity);

                    // If user is asking generally without any fields, prompt for details instead of failing or going to RAG
                    if (fields.isEmpty()) {
                        String entityName = targetEntity.label() != null ? targetEntity.label().toLowerCase(Locale.ROOT) : targetEntity.name();
                        String reply = (locale != null && locale.toLowerCase(Locale.ROOT).startsWith("vi"))
                                ? "Tôi có thể giúp bạn tạo " + entityName + " trong " + app.name() + ". Vui lòng cho tôi biết thông tin chi tiết (ví dụ: ngày giờ, tên khách hàng hoặc nội dung) để tôi chuẩn bị nhé!"
                                : "I can help you create " + entityName + " in " + app.name() + ". Please provide the details (e.g. date/time, customer name, or description) to proceed!";
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
                                null
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
                            payloadHash
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

        String systemPrompt = buildSystemPrompt(installed, catalog, role, locale);
        String prompt = "User statement: " + content;

        try {
            var structuredResponse = aiInferenceApi.completeJson(new AiInferenceApi.StructuredJsonRequest(
                    prompt,
                    systemPrompt,
                    "bizapp_intent.schema.json",
                    "vylinh",
                    0.1f,
                    512
            ));

            String json = structuredResponse.jsonOutput();
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return RoutingResult.none();
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
                return RoutingResult.none();
            }

            String rawKind = root.path("appKind").asText(null);
            String appKind = resolveAppKind(rawKind != null ? rawKind : content, knownKinds, aliasToKind);
            if (appKind == null && (intent == Intent.INSTALL || intent == Intent.UNINSTALL || intent == Intent.APP_DETAIL || intent == Intent.APP_DATA || intent == Intent.APP_RECORD_CREATE || intent == Intent.APP_RECORD_UPDATE || intent == Intent.APP_RECORD_ARCHIVE || intent == Intent.APP_RECORD_SUBMIT)) {
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
                actionCard = Map.of(
                        "type", "bizapp_action",
                        "actionId", UUID.randomUUID().toString(),
                        "kind", "bizapp.draft",
                        "status", "PROPOSED",
                        "requiresRole", "ORG_OWNER",
                        "payload", sessionId != null ? Map.of("sessionId", sessionId.toString()) : Map.of()
                );
            } else if (intent == Intent.APP_RECORD_CREATE || intent == Intent.APP_RECORD_UPDATE
                    || intent == Intent.APP_RECORD_ARCHIVE || intent == Intent.APP_RECORD_SUBMIT) {
                if (!recordActionsEnabled) {
                    return RoutingResult.none();
                }
                var instOpt = installed.stream().filter(i -> i.kind().equalsIgnoreCase(targetKind)).findFirst();
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
                    if (fields.isEmpty() && intent == Intent.APP_RECORD_CREATE) {
                        fields.putAll(extractFieldsFromPrompt(content, targetEntity));
                    }

                    List<Map<String, String>> invalidList = (intent == Intent.APP_RECORD_CREATE)
                            ? validateRequiredFields(fields, targetEntity.fields())
                            : List.of();
                    String status = invalidList.isEmpty() ? "PROPOSED" : "INVALID";
                    String payloadHash = invalidList.isEmpty() ? computePayloadHash(fields) : null;

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
                            null,
                            fields,
                            buildFieldLabels(targetEntity.fields()),
                            status,
                            invalidList,
                            sessionId,
                            payloadHash
                    );
                }
            }

            return new RoutingResult(intent, appKind, null, confidence, reply, reason, offer, actionCard);

        } catch (Exception ex) {
            log.warn("Intent classification failed: {}", ex.getMessage());
            return RoutingResult.none();
        }
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
        if (hasAdmin || hasWrite || hasVocab || hasDetail) {
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

    private String buildSystemPrompt(
            List<InstalledAppSummary> installed,
            List<MarketplaceTemplateSummary> catalog,
            String role,
            String locale
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
        sb.append("- CREATE_APP: user wants to build or create a new app\n");
        sb.append("- APP_RECORD_CREATE: user wants to create/add/insert a new record/data into an app\n");
        sb.append("- APP_RECORD_UPDATE: user wants to update/edit an existing record in an app\n");
        sb.append("- APP_RECORD_ARCHIVE: user wants to delete/archive a record in an app\n");
        sb.append("- APP_RECORD_SUBMIT: user wants to submit a record for approval\n");
        sb.append("- NONE: regular document questions, general conversation, or guidelines/policy questions\n\n");

        sb.append("Installed Apps: ");
        sb.append(installed.stream().map(InstalledAppSummary::kind).toList()).append("\n");
        sb.append("Marketplace Catalog: ");
        sb.append(catalog.stream().map(MarketplaceTemplateSummary::kind).toList()).append("\n");
        sb.append("Caller Role: ").append(role != null ? role : "MEMBER").append("\n");
        sb.append("Locale: ").append(locale != null ? locale : "vi").append("\n\n");
        sb.append("Output JSON schema:\n");
        sb.append("{\"intent\":\"NONE|LIST_APPS|APP_DETAIL|APP_DATA|INSTALL|UNINSTALL|SUGGEST|CREATE_APP|APP_RECORD_CREATE|APP_RECORD_UPDATE|APP_RECORD_ARCHIVE|APP_RECORD_SUBMIT\",");
        sb.append("\"appKind\":\"kind or null\",");
        sb.append("\"entity\":\"entity name or null\",");
        sb.append("\"fields\":{\"fieldName\":\"value\"},");
        sb.append("\"confidence\":0.0-1.0,");
        sb.append("\"reply\":\"conversational explanation for user in the given locale\",");
        sb.append("\"reason\":\"brief reason\"}\n\n");
        sb.append("Few-shot Examples:\n");
        sb.append("- \"Ứng dụng kho bao gồm những trường nào?\" -> {\"intent\":\"APP_DETAIL\",\"appKind\":\"inventory\",\"confidence\":0.98,\"reply\":\"Chi tiết các trường của ứng dụng\"}\n");
        sb.append("- \"Hiện tại đang có bao nhiêu ứng dụng đang được cài đặt?\" -> {\"intent\":\"LIST_APPS\",\"appKind\":null,\"confidence\":0.98,\"reply\":\"Danh sách ứng dụng\"}\n");
        sb.append("- \"Cài đặt ứng dụng quản lý tài sản\" -> {\"intent\":\"INSTALL\",\"appKind\":\"asset-mgmt\",\"confidence\":0.98,\"reply\":\"Cài đặt ứng dụng\"}\n");
        sb.append("- \"Thêm tài sản mới tên Máy in HP vào sổ tài sản\" -> {\"intent\":\"APP_RECORD_CREATE\",\"appKind\":\"asset-registry\",\"entity\":\"assets\",\"fields\":{\"name\":\"Máy in HP\"},\"confidence\":0.95,\"reply\":\"Chuẩn bị thêm tài sản\"}\n");
        sb.append("- \"Quy trình thêm khách hàng gồm mấy bước?\" -> {\"intent\":\"NONE\",\"appKind\":null,\"confidence\":0.98,\"reply\":\"\"}\n");
        return sb.toString();
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

    public static String cleanNameValue(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        cleaned = cleaned.replaceAll("(?i)^(?:kh[aá]ch\\s+(?:h[aà]ng\\s+)?(?:l[aà]|t[eê]n)?|t[eê]n\\s+(?:kh[aá]ch\\s+)?(?:l[aà])?|l[aà]|ng[uư][oờ]i)\\s+", "").trim();
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

    public static Map<String, Object> extractFieldsFromPrompt(String prompt, EntityDetail entity) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (prompt == null || prompt.isBlank() || entity == null || entity.fields() == null) {
            return extracted;
        }

        // 1. Generic key-value extraction: e.g. "Service là xây dựng", "Dịch vụ là tư vấn", "Tên khách: Nguyễn Văn An"
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
                    } else if ("name".equalsIgnoreCase(f.name()) || "customerName".equalsIgnoreCase(f.name())) {
                        extracted.put(f.name(), cleanNameValue(rawVal));
                    } else {
                        extracted.put(f.name(), rawVal);
                    }
                }
            }
        }

        // 2. Email extraction
        Matcher emailMatcher = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").matcher(prompt);
        if (emailMatcher.find()) {
            extracted.put("email", emailMatcher.group());
        }

        // 3. Phone extraction
        Matcher phoneMatcher = Pattern.compile("(?<!\\d)(0\\d{8,10})(?!\\d)").matcher(prompt);
        if (phoneMatcher.find()) {
            extracted.put("phone", phoneMatcher.group(1));
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

        if (foundIsoDate != null) {
            extracted.put("bookingDate", foundIsoDate);
            extracted.put("date", foundIsoDate);
            for (FieldDetail f : entity.fields()) {
                String fn = f.name().toLowerCase(Locale.ROOT);
                if ("date".equalsIgnoreCase(f.type()) || fn.contains("date")) {
                    extracted.putIfAbsent(f.name(), foundIsoDate);
                }
            }
        }

        if (foundFormattedTime != null) {
            extracted.put("time", foundFormattedTime);
            for (FieldDetail f : entity.fields()) {
                String fn = f.name().toLowerCase(Locale.ROOT);
                if (fn.contains("time")) {
                    extracted.putIfAbsent(f.name(), foundFormattedTime);
                }
            }
        }

        Matcher notesMatcher = Pattern.compile("(?i)\\b(?:ghi\\s*ch[uú]|notes?|m[oô]\\s*t[aả])\\s*[:\\-\\s]?\\s*([^,;\\n]+)").matcher(prompt);
        if (notesMatcher.find()) {
            extracted.put("notes", notesMatcher.group(1).trim());
        }
        // 5. Name extraction
        Matcher nameMatcher = Pattern.compile("(?i)\\b(?:t[eê]n(?:\\s+l[aà]|\\s+kh[aá]ch(?:\\s+l[aà])?|\\s*:)?|\\bname\\s*:|\\bname)\\s+([^\\d,;:/\n]+?)(?=[,.;\\n]|\\s+(?:s[oố]|sdt|phone|email|ghi|note|v[aà]o|cho|service|dich\\s+vu)|$)", Pattern.UNICODE_CHARACTER_CLASS).matcher(prompt);
        if (nameMatcher.find()) {
            String name = cleanNameValue(nameMatcher.group(1));
            if (!name.isBlank() && !name.equalsIgnoreCase("moi") && !name.equalsIgnoreCase("mới")) {
                extracted.put("name", name);
            }
        }
        if (!extracted.containsKey("name") && !extracted.containsKey("customerName")) {
            Matcher personMatcher = Pattern.compile("(?i)\\b(?:kh[aá]ch(?:\\s+h[aà]ng)?|ng[uư][oờ]i|lead|customer|client)\\s+(?:l[aà]\\s+|t[eê]n\\s+|name\\s+|is\\s+)?([^\\d,;:/\n]+?)(?=[,.;\\n]|\\s+(?:s[oố]|sdt|phone|email|ghi|note|v[aà]o|cho|service|dich\\s+vu|at|on)|$)", Pattern.UNICODE_CHARACTER_CLASS).matcher(prompt);
            if (personMatcher.find()) {
                String name = cleanNameValue(personMatcher.group(1));
                if (!name.isBlank() && !name.equalsIgnoreCase("moi") && !name.equalsIgnoreCase("mới")) {
                    extracted.put("name", name);
                }
            }
        }

        // 6. Comma name extraction fallback (e.g. "Hôm nay 13h45, Nguyễn Văn A")
        if (!extracted.containsKey("name") && !extracted.containsKey("customerName") && !extracted.containsKey("title")) {
            Matcher commaNameMatcher = Pattern.compile("(?:,|–|-)\\s*([\\p{Lu}][\\p{L}\\s]{1,30}?)(?=[,.;\\n]|$)", Pattern.UNICODE_CHARACTER_CLASS).matcher(prompt);
            while (commaNameMatcher.find()) {
                String val = cleanNameValue(commaNameMatcher.group(1));
                String valLower = val.toLowerCase(Locale.ROOT);
                if (!val.isBlank() && val.length() >= 2 && !valLower.contains("service") && !valLower.contains("dich vu") && !valLower.contains("la ") && !valLower.contains("là ")) {
                    extracted.put("name", val);
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

        // 8. Map aliases and strictly whitelist against entity fields
        boolean hasName = entity.fields().stream().anyMatch(f -> "name".equalsIgnoreCase(f.name()));
        boolean hasCustomerName = entity.fields().stream().anyMatch(f -> "customerName".equalsIgnoreCase(f.name()));
        if (hasCustomerName && !hasName && extracted.containsKey("name")) {
            extracted.put("customerName", extracted.remove("name"));
        }
        if (hasName && !hasCustomerName && extracted.containsKey("customerName")) {
            extracted.put("name", extracted.remove("customerName"));
        }

        boolean hasEmail = entity.fields().stream().anyMatch(f -> "email".equalsIgnoreCase(f.name()));
        boolean hasCustomerEmail = entity.fields().stream().anyMatch(f -> "customerEmail".equalsIgnoreCase(f.name()));
        if (hasCustomerEmail && !hasEmail && extracted.containsKey("email")) {
            extracted.put("customerEmail", extracted.remove("email"));
        }
        if (hasEmail && !hasCustomerEmail && extracted.containsKey("customerEmail")) {
            extracted.put("email", extracted.remove("customerEmail"));
        }

        boolean hasBookingDate = entity.fields().stream().anyMatch(f -> "bookingDate".equalsIgnoreCase(f.name()));
        if (hasBookingDate && extracted.containsKey("date") && !extracted.containsKey("bookingDate")) {
            extracted.put("bookingDate", extracted.get("date"));
        }

        // STRICT WHITELIST: remove any key not declared in entity fields
        Set<String> declared = new HashSet<>();
        for (FieldDetail f : entity.fields()) {
            declared.add(f.name());
        }
        extracted.keySet().removeIf(k -> !declared.contains(k));

        if (extracted.containsKey("name")) {
            extracted.put("name", cleanNameValue(String.valueOf(extracted.get("name"))));
        }
        if (extracted.containsKey("customerName")) {
            extracted.put("customerName", cleanNameValue(String.valueOf(extracted.get("customerName"))));
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
            String payloadHash
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
        card.put("requiresRole", "ORG_OWNER");
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
            sb.append("Để hoàn tất đặt lịch trong ").append(appName != null ? appName : "ứng dụng");
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
                    } else if ("bookingDate".equalsIgnoreCase(fName) || "date".equalsIgnoreCase(fName) || (f != null && "date".equalsIgnoreCase(f.type()))) {
                        sb.append("ví dụ: Hôm nay 14h, hoặc ngày mai lúc 9h");
                    } else if ("customerEmail".equalsIgnoreCase(fName) || "email".equalsIgnoreCase(fName) || (f != null && "email".equalsIgnoreCase(f.format()))) {
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
