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
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

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
        CREATE_APP
    }

    public record RoutingResult(
            Intent intent,
            String appKind,
            BigDecimal confidence,
            String reply,
            String reason,
            AppActionOffer offer,
            Map<String, Object> actionCard
    ) {
        public static RoutingResult none() {
            return new RoutingResult(Intent.NONE, null, BigDecimal.ZERO, null, "Default RAG route", null, null);
        }
    }

    private static final Pattern ADMIN_VERBS = Pattern.compile(
            "\\b(cai|cai dat|install|go|go cai dat|uninstall|xoa app|remove app|danh sach app|list apps?|show apps?|xem app|tao app|lap app|them app|create app|build app|compose app|goi y app|suggest apps?|ung dung|marketplace)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern APP_VOCAB = Pattern.compile(
            "\\b(crm|lead|leads|khach hang|dat lich|booking|appointments?|cuoc hen|ky so|chu ky|trinh ky|signature|signature-request)\\b",
            Pattern.CASE_INSENSITIVE
    );

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

    @Value("${app.chat.intent-confidence-threshold:0.75}")
    private double confidenceThreshold = 0.75;

    @Value("${app.chat.intent-classify-all-turns:false}")
    private boolean classifyAllTurns = false;

    public void setActionsEnabled(boolean actionsEnabled) {
        this.actionsEnabled = actionsEnabled;
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
        if (!actionsEnabled) {
            return RoutingResult.none();
        }

        if (content == null || content.isBlank()) {
            return RoutingResult.none();
        }

        if (!classifyAllTurns && !matchesDeterministicGate(content)) {
            return RoutingResult.none();
        }

        List<InstalledAppSummary> installed = bizAppRuntimeApi.listInstalledSummary(tenantId);
        List<MarketplaceTemplateSummary> catalog = bizAppRuntimeApi.listMarketplaceSummary(tenantId);

        Set<String> knownKinds = new HashSet<>();
        installed.forEach(i -> knownKinds.add(i.kind().toLowerCase(Locale.ROOT)));
        catalog.forEach(c -> knownKinds.add(c.kind().toLowerCase(Locale.ROOT)));

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

            String appKind = root.path("appKind").asText(null);
            if (appKind != null && !appKind.isBlank() && !"null".equalsIgnoreCase(appKind)) {
                appKind = appKind.trim().toLowerCase(Locale.ROOT);
                if (intent == Intent.INSTALL || intent == Intent.UNINSTALL || intent == Intent.APP_DETAIL || intent == Intent.APP_DATA) {
                    if (!knownKinds.contains(appKind)) {
                        log.info("Unknown appKind {} proposed by classifier; downgrading to NONE", appKind);
                        return RoutingResult.none();
                    }
                } else {
                    appKind = knownKinds.contains(appKind) ? appKind : null;
                }
            } else {
                appKind = null;
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
            }

            return new RoutingResult(intent, appKind, confidence, reply, reason, offer, actionCard);

        } catch (Exception ex) {
            log.warn("Intent classification failed: {}", ex.getMessage());
            return RoutingResult.none();
        }
    }

    public static boolean matchesDeterministicGate(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = removeDiacritics(query.toLowerCase(Locale.ROOT));
        boolean hasVerb = ADMIN_VERBS.matcher(normalized).find();
        boolean hasVocab = APP_VOCAB.matcher(normalized).find();
        return hasVerb || hasVocab;
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
        sb.append("- APP_DATA: user asks about records or domain data inside an app (e.g. leads in CRM, appointments in booking)\n");
        sb.append("- INSTALL: user wants to install an app from the catalog\n");
        sb.append("- UNINSTALL: user wants to uninstall/remove an installed app\n");
        sb.append("- SUGGEST: user asks for app suggestions/recommendations\n");
        sb.append("- CREATE_APP: user wants to build or create a new app\n");
        sb.append("- NONE: regular document questions, general conversation, or sensitive queries without admin verbs\n\n");

        sb.append("Installed Apps: ");
        sb.append(installed.stream().map(InstalledAppSummary::kind).toList()).append("\n");
        sb.append("Marketplace Catalog: ");
        sb.append(catalog.stream().map(MarketplaceTemplateSummary::kind).toList()).append("\n");
        sb.append("Caller Role: ").append(role != null ? role : "MEMBER").append("\n");
        sb.append("Locale: ").append(locale != null ? locale : "vi").append("\n\n");
        sb.append("Output JSON schema:\n");
        sb.append("{\"intent\":\"NONE|LIST_APPS|APP_DETAIL|APP_DATA|INSTALL|UNINSTALL|SUGGEST|CREATE_APP\",");
        sb.append("\"appKind\":\"kind or null\",");
        sb.append("\"confidence\":0.0-1.0,");
        sb.append("\"reply\":\"conversational explanation for user in the given locale\",");
        sb.append("\"reason\":\"brief reason\"}");
        return sb.toString();
    }

    private Map<String, Object> buildActionCard(AppActionOffer offer, String status, UUID sessionId) {
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
}
