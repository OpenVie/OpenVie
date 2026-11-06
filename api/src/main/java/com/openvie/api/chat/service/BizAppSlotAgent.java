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

package com.openvie.api.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.chat.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * LLM-assisted slot extraction for BizApp record actions (W7.4).
 *
 * Fully manifest-driven ("plug and play"): the extraction prompt and the
 * structured-output JSON schema are generated at runtime from the installed
 * app's {@link EntityDetail}/{@link FieldDetail} contract — no per-app
 * knowledge exists in code, so every installed or future marketplace app is
 * supported without code changes.
 *
 * The agent returns only normalized slot values. Answers/cards/validation stay
 * deterministic in {@code BizAppIntentRouter} and {@code BizAppPayloadValidator}.
 * On any LLM failure it returns an empty map so the router degrades to the
 * deterministic (regex) extraction path.
 */
@Component
@Slf4j(topic = "SLOT-AGENT")
public class BizAppSlotAgent {

    private static final String DEFAULT_MODEL_ID = "vylinh";

    private final AiInferenceApi aiInferenceApi;
    private final ObjectMapper objectMapper;

    @Value("${app.chat.bizapp-records.slot-mode:llm}")
    private String slotMode = "llm";

    public BizAppSlotAgent(AiInferenceApi aiInferenceApi, ObjectMapper objectMapper) {
        this.aiInferenceApi = aiInferenceApi;
        this.objectMapper = objectMapper;
    }

    public void setSlotMode(String slotMode) {
        this.slotMode = slotMode == null ? "regex" : slotMode;
    }

    /** True when the LLM-assisted extraction mode is enabled ("llm"). */
    public boolean llmEnabled() {
        return "llm".equalsIgnoreCase(slotMode);
    }

    /**
     * Extracts slot values for the given entity from the recent conversation.
     * Returns normalized values for declared fields only; empty when the LLM
     * produced nothing usable.
     */
    public Map<String, Object> extractSlots(
            EntityDetail entity,
            String appName,
            String locale,
            List<ChatMessage> conversation,
            String currentMessage
    ) {
        if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return Map.of();
        }
        try {
            String systemPrompt = buildSystemPrompt(entity, appName, locale, conversation, currentMessage);
            var response = aiInferenceApi.completeJson(new AiInferenceApi.StructuredJsonRequest(
                    "Extract the requested slot values. Respond with JSON only.",
                    systemPrompt,
                    buildJsonSchema(entity),
                    DEFAULT_MODEL_ID,
                    0.1f,
                    512
            ));
            String json = response == null ? null : response.jsonOutput();
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return Map.of();
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode fieldsNode = root.path("fields");
            if (!fieldsNode.isObject()) {
                return Map.of();
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fieldIterator = fieldsNode.fields();
            while (fieldIterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = fieldIterator.next();
                JsonNode value = entry.getValue();
                if (value == null || value.isNull() || value.isMissingNode()) {
                    continue;
                }
                if (value.isNumber()) {
                    raw.put(entry.getKey(), value.decimalValue());
                } else if (value.isBoolean()) {
                    raw.put(entry.getKey(), value.booleanValue());
                } else {
                    raw.put(entry.getKey(), value.asText());
                }
            }
            return dropUngroundedText(normalize(raw, entity), entity, userTexts(conversation, currentMessage));
        } catch (Exception ex) {
            log.warn("LLM slot extraction failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    // ------------------------------------------------------------------
    // Normalization: strict field whitelist + type-aware coercion. The LLM
    // answers may still drift; deterministic enforcement mirrors the record
    // manifest so validation downstream stays the single source of truth.
    // ------------------------------------------------------------------
    public Map<String, Object> normalize(Map<String, Object> raw, EntityDetail entity) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (FieldDetail f : entity.fields()) {
            Object value = firstMatch(raw, f.name());
            if (value == null) continue;
            String str = String.valueOf(value).trim();
            if (str.isEmpty()) continue;
            if ("enum".equalsIgnoreCase(f.type()) && f.options() != null && !f.options().isEmpty()) {
                String mapped = com.openvie.api.chat.query.BizAppIntentRouter.mapEnumOption(str, f);
                if (mapped == null) {
                    continue; // invalid enum values are dropped, not stored
                }
                normalized.put(f.name(), mapped);
            } else if ("date".equalsIgnoreCase(f.type())) {
                String resolved = resolveDateValue(str);
                if (resolved == null) {
                    continue;
                }
                normalized.put(f.name(), resolved);
            } else if ("number".equalsIgnoreCase(f.type())) {
                try {
                    normalized.put(f.name(), new BigDecimal(str));
                } catch (NumberFormatException ignored) {
                    // drop non-numeric values for number fields
                }
            } else if ("boolean".equalsIgnoreCase(f.type())) {
                if (str.matches("(?i)true|yes|co|c\\u00f3|d\\u1eabng|1")) {
                    normalized.put(f.name(), Boolean.TRUE);
                } else if (str.matches("(?i)false|no|khong|0")) {
                    normalized.put(f.name(), Boolean.FALSE);
                }
            } else {
                String text = isPersonName(f)
                        ? com.openvie.api.chat.query.BizAppIntentRouter.stripHonorific(str)
                        : str;
                if (!text.isEmpty()) {
                    normalized.put(f.name(), text);
                }
            }
        }
        return normalized;
    }

    private Object firstMatch(Map<String, Object> raw, String fieldName) {
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey().equalsIgnoreCase(fieldName)) return e.getValue();
        }
        return null;
    }

    /** Same name-field convention the router's deterministic extractor already uses. */
    private static boolean isPersonName(FieldDetail f) {
        return "name".equalsIgnoreCase(f.name()) || "customerName".equalsIgnoreCase(f.name());
    }

    private String resolveDateValue(String raw) {
        String str = raw.trim();
        int tIndex = str.indexOf('T');
        if (tIndex > 0) {
            try {
                LocalDate date = LocalDate.parse(str.substring(0, tIndex));
                String timePart = str.substring(tIndex + 1);
                try {
                    LocalTime time = LocalTime.parse(timePart);
                    if (timePart.length() > 5) {
                        return date + "T" + timePart;
                    }
                    return date + "T" + String.format("%02d:%02d", time.getHour(), time.getMinute());
                } catch (DateTimeParseException ex) {
                    return date.toString();
                }
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        try {
            return LocalDate.parse(str).toString();
        } catch (DateTimeParseException ignored) {
            // fall through to natural-language resolution
        }
        return com.openvie.api.chat.query.BizAppIntentRouter.resolveDateString(str);
    }

    // ------------------------------------------------------------------
    // Prompt + JSON schema, both generated from the manifest contract only.
    // ------------------------------------------------------------------
    String buildSystemPrompt(
            EntityDetail entity,
            String appName,
            String locale,
            List<ChatMessage> conversation,
            String currentMessage
    ) {
        boolean isVi = locale == null || !locale.toLowerCase(Locale.ROOT).startsWith("en");
        StringBuilder sb = new StringBuilder();
        if (isVi) {
            sb.append("Bạn là bộ trích xuất thông tin dạng JSON cho lệnh tạo/cập nhật bản ghi")
              .append(appName != null ? " trong ứng dụng " + appName : "").append(".\n");
            sb.append("Chỉ trích thông tin NÓI RÕ trong hội thoại. KHÔNG suy đoán, không điền giá trị mẫu.\n");
            sb.append("Các trường không được nêu trong hội thoại phải BỎ KHỎI JSON.\n");
            sb.append("Tên người: chỉ ghi tên, BỎ các từ xưng hô như anh, chị, em, ông, bà, cô, chú, bác.\n");
            sb.append("Ngày: trả về dạng ISO yyyy-MM-dd (hôm nay = ")
              .append(LocalDate.now()).append("). Nếu người dùng nêu giờ cụ thể, trả về yyyy-MM-ddTHH:mm.\n");
            sb.append("Số: trả về dạng số. Giá trị enum: trả về đúng tùy chọn được liệt kê.\n");
            sb.append("Trường của thực thể ").append(entity.name())
              .append(entity.label() != null ? " (" + entity.label() + ")" : "").append(":\n");
            for (FieldDetail f : entity.fields()) {
                sb.append("- key \"").append(f.name()).append("\"");
                if (f.label() != null && !f.label().isBlank()) sb.append(" — ").append(f.label());
                sb.append("; type=").append(f.type() == null ? "string" : f.type());
                if (f.required()) sb.append(" (bắt buộc)");
                if (f.format() != null && !f.format().isBlank()) sb.append("; format=").append(f.format());
                if (f.options() != null && !f.options().isEmpty()) {
                    sb.append("; options: ").append(String.join(", ", f.options()));
                    if (f.optionAliases() != null && !f.optionAliases().isEmpty()) {
                        List<String> aliases = new ArrayList<>();
                        f.optionAliases().forEach((opt, list) -> aliases.addAll(list));
                        sb.append("; accepts: ").append(String.join(", ", aliases));
                    }
                }
                sb.append('\n');
            }
        } else {
            sb.append("You are a JSON slot extractor for record actions")
              .append(appName != null ? " in the " + appName + " app" : "").append(".\n");
            sb.append("Extract ONLY information explicitly stated in the conversation. Never guess or invent values.\n");
            sb.append("Omit any field not provided in the conversation.\n");
            sb.append("Person names: output the name only, without forms of address such as anh, chị, em, ông, bà, Mr, Mrs.\n");
            sb.append("Dates: output ISO yyyy-MM-dd (today = ").append(LocalDate.now()).append("). Include an explicit time as yyyy-MM-ddTHH:mm.\n");
            sb.append("Numbers: output numeric values. Enum values: output the listed option verbatim.\n");
            sb.append("Entity fields for ").append(entity.name())
              .append(entity.label() != null ? " (" + entity.label() + ")" : "").append(":\n");
            for (FieldDetail f : entity.fields()) {
                sb.append("- key \"").append(f.name()).append("\"");
                if (f.label() != null && !f.label().isBlank()) sb.append(" — ").append(f.label());
                sb.append("; type=").append(f.type() == null ? "string" : f.type());
                if (f.required()) sb.append(" (required)");
                if (f.format() != null && !f.format().isBlank()) sb.append("; format=").append(f.format());
                if (f.options() != null && !f.options().isEmpty()) {
                    sb.append("; options: ").append(String.join(", ", f.options()));
                    if (f.optionAliases() != null && !f.optionAliases().isEmpty()) {
                        List<String> aliases = new ArrayList<>();
                        f.optionAliases().forEach((opt, list) -> aliases.addAll(list));
                        sb.append("; accepts: ").append(String.join(", ", aliases));
                    }
                }
                sb.append('\n');
            }
        }
        sb.append(isVi ? "\nHội thoại gần đây:\n" : "\nRecent conversation:\n");
        sb.append(renderConversation(conversation, currentMessage));
        return sb.toString().trim();
    }

    /**
     * Texts the extractor may rely on: user turns inside the 8-message lookback
     * window plus the current message. Assistant turns are excluded on purpose:
     * the bot's own replies and cards echo earlier slot values, so feeding them
     * back lets one wrong extraction reinforce itself on the next turn.
     */
    List<String> userTexts(List<ChatMessage> conversation, String currentMessage) {
        List<String> texts = new ArrayList<>();
        int size = conversation == null ? 0 : conversation.size();
        for (int i = Math.max(0, size - 8); i < size; i++) {
            ChatMessage msg = conversation.get(i);
            if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) continue;
            if (!"user".equalsIgnoreCase(msg.getRole())) continue;
            String content = msg.getContent().strip();
            texts.add(content.length() > 600 ? content.substring(0, 600) : content);
        }
        if (currentMessage != null && !currentMessage.isBlank()) {
            texts.add(currentMessage.strip());
        }
        return texts;
    }

    String renderConversation(List<ChatMessage> conversation, String currentMessage) {
        StringBuilder sb = new StringBuilder();
        for (String text : userTexts(conversation, currentMessage)) {
            sb.append("user: ").append(text).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Grounding: the prompt asks the model not to invent values, but nothing
    // enforces it. Free-text slots (names, emails, notes...) must therefore
    // occur in what the user actually typed, or they are dropped and the
    // router asks for them. Enum/date/number/boolean slots are already
    // constrained by normalize().
    // ------------------------------------------------------------------
    private Map<String, Object> dropUngroundedText(
            Map<String, Object> slots, EntityDetail entity, List<String> userTexts) {
        if (slots.isEmpty()) return slots;
        List<String> foldedTexts = userTexts.stream().map(BizAppSlotAgent::fold).toList();
        Map<String, Object> grounded = new LinkedHashMap<>();
        for (FieldDetail f : entity.fields()) {
            Object value = slots.get(f.name());
            if (value == null) continue;
            if (isFreeText(f) && !mentioned(String.valueOf(value), foldedTexts)) {
                log.info("Dropping LLM slot '{}': value does not occur in the user's messages", f.name());
                continue;
            }
            grounded.put(f.name(), value);
        }
        return grounded;
    }

    private static boolean isFreeText(FieldDetail f) {
        String type = f.type();
        return type == null
                || !(type.equalsIgnoreCase("enum") || type.equalsIgnoreCase("date")
                || type.equalsIgnoreCase("number") || type.equalsIgnoreCase("boolean"));
    }

    /** Phone-like slot values ("0912345678", "+84 912.345.678") are compared by digits, not by spelling. */
    private static final Pattern PHONE_LIKE = Pattern.compile("\\+?[\\d\\s.()\\-]+");

    /**
     * True when the value occurs in a user text as whole words (so "Nguyễn Văn A"
     * does not match inside "Nguyễn Văn An"), or, for phone-like values of at
     * least 6 digits, when the digit sequence occurs regardless of spacing or
     * punctuation ("0912 345 678" == "0912345678").
     */
    private static boolean mentioned(String value, List<String> foldedTexts) {
        String needle = fold(value);
        if (needle.isEmpty()) return false;
        Pattern wholeWords = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])");
        String digits = PHONE_LIKE.matcher(needle).matches() ? needle.replaceAll("\\D", "") : "";
        for (String text : foldedTexts) {
            if (wholeWords.matcher(text).find()) return true;
            if (digits.length() >= 6 && text.replaceAll("\\D", "").contains(digits)) return true;
        }
        return false;
    }

    /** Case-, diacritic- and whitespace-insensitive form ("Nguyễn  Văn B" == "nguyen van b"). */
    private static String fold(String s) {
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * Runtime-generated structured-output schema. Field properties mirror the
     * installed app manifest (types, enums); all slots are optional because the
     * agent must omit unknown values — required-slot enforcement belongs to the
     * deterministic router validation, not to the model grammar.
     */
    String buildJsonSchema(EntityDetail entity) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$id", "https://openvie.local/contracts/bizapp/bizapp_slots.schema.json");
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        required.add("fields");
        ObjectNode properties = root.putObject("properties");
        ObjectNode fields = properties.putObject("fields");
        fields.put("type", "object");
        fields.put("additionalProperties", false);
        ObjectNode fieldProps = fields.putObject("properties");
        for (FieldDetail f : entity.fields()) {
            ObjectNode prop = fieldProps.putObject(f.name());
            switch (f.type() == null ? "string" : f.type().toLowerCase(Locale.ROOT)) {
                case "number" -> prop.put("type", "number");
                case "boolean" -> prop.put("type", "boolean");
                case "enum" -> {
                    prop.put("type", "string");
                    if (f.options() != null && !f.options().isEmpty()) {
                        ArrayNode options = prop.putArray("enum");
                        f.options().forEach(options::add);
                    }
                }
                default -> prop.put("type", "string");
            }
        }
        return root.toString();
    }
}
