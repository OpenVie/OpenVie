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
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonRequest;
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonResponse;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.query.BizAppIntentRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies W7.4: the LLM slot agent is fully manifest-driven (plug and play),
 * normalizes values against the manifest contract, and degrades to an empty
 * map on any LLM failure so the router keeps its deterministic behavior.
 */
class BizAppSlotAgentTest {

    private AiInferenceApi aiInferenceApi;
    private ObjectMapper objectMapper;
    private BizAppSlotAgent agent;

    private final EntityDetail bookingEntity = new EntityDetail(
            "bookings", "Lịch hẹn",
            List.of(
                    new FieldDetail("customerName", "string", true, "Tên khách"),
                    new FieldDetail("customerEmail", "string", true, "Email khách",
                            List.of(), "email"),
                    new FieldDetail("service", "enum", true, "Dịch vụ",
                            List.of("consultation", "tour", "meeting-room"),
                            null,
                            Map.of("meeting-room", "Phòng họp"),
                            Map.of("meeting-room", List.of("phòng họp", "thuê phòng", "họp"),
                                    "tour", List.of("tham quan"))),
                    new FieldDetail("bookingDate", "date", true, "Thời gian hẹn"),
                    new FieldDetail("guests", "number", false, "Số lượng")
            )
    );

    @BeforeEach
    void setUp() {
        aiInferenceApi = mock(AiInferenceApi.class);
        objectMapper = new ObjectMapper();
        agent = new BizAppSlotAgent(aiInferenceApi, objectMapper);
        agent.setSlotMode("llm");
    }

    private ChatMessage message(String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    @Test
    void llmModeToggleMatchesSlotModeConfiguration() {
        assertTrue(agent.llmEnabled());
        agent.setSlotMode("regex");
        assertFalse(agent.llmEnabled());
    }

    @Test
    void extractSlotsNormalizesValuesAndStrictlyWhitelistsManifestFields() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\",\"bookingDate\":\"2026-11-05T21:22\","
                + "\"service\":\"phòng họp\",\"guests\":\"3\",\"junk\":\"x\"}}",
                "vylinh", 100, 30
        ));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN",
                List.of(message("user", "Bạn đặt lịch giúp tôi")), "Nguyễn Văn A, hôm nay, lúc 21h22");

        assertEquals("Nguyễn Văn A", slots.get("customerName"));
        assertEquals("2026-11-05T21:22", slots.get("bookingDate"));
        assertEquals("meeting-room", slots.get("service"), "enum aliases map to manifest option keys");
        assertEquals(new BigDecimal("3"), slots.get("guests"));
        assertFalse(slots.containsKey("junk"), "unknown keys must be dropped");
    }

    @Test
    void extractSlotsResolvesNaturalDatesAgainstToday() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"bookingDate\":\"hôm nay\"}}", "vylinh", 100, 10
        ));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(), "hôm nay");

        assertEquals(LocalDate.now().toString(), slots.get("bookingDate"));
    }

    @Test
    void extractSlotsDropsUnmappedEnumValues() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"service\":\"không tồn tại\"}}", "vylinh", 100, 10
        ));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(), "");

        assertFalse(slots.containsKey("service"), "invalid enum values are dropped, not stored");
    }

    @Test
    void extractSlotsDropsTextSlotsTheUserNeverTyped() {
        // The model invents a name and an email; only service/date are grounded in the message.
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\",\"customerEmail\":\"a@example.com\","
                + "\"service\":\"consultation\",\"bookingDate\":\"2026-11-07\"}}",
                "vylinh", 100, 30
        ));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(),
                "Tạo cho tôi lịch với Nguyễn Văn B vào ngày mai, tư vấn");

        assertFalse(slots.containsKey("customerName"), "name not typed by the user must be dropped");
        assertFalse(slots.containsKey("customerEmail"), "email not typed by the user must be dropped");
        assertEquals("consultation", slots.get("service"));
        assertEquals("2026-11-07", slots.get("bookingDate"));
    }

    @Test
    void assistantEchoNeitherGroundsNorFeedsTheExtractionPrompt() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\"}}", "vylinh", 100, 10
        ));
        List<ChatMessage> conversation = List.of(
                message("user", "Tạo lịch với Nguyễn Văn B"),
                message("assistant", "Tôi đã chuẩn bị: Tên khách Nguyễn Văn A, Email khách a@example.com"));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", conversation,
                "Nguyễn Văn B cơ mà sao lại là Nguyễn Văn A");

        ArgumentCaptor<StructuredJsonRequest> captor = ArgumentCaptor.forClass(StructuredJsonRequest.class);
        verify(aiInferenceApi).completeJson(captor.capture());
        String prompt = captor.getValue().systemPrompt();
        assertFalse(prompt.contains("a@example.com"), "assistant turns must not reach the prompt");
        assertFalse(prompt.contains("assistant:"));
        assertTrue(prompt.contains("user: Tạo lịch với Nguyễn Văn B"));
        // The user did type "Nguyễn Văn A" in the correction, so that is the user's own text and stays grounded.
        assertEquals("Nguyễn Văn A", slots.get("customerName"));

        // Same model output, but the name exists only in the assistant echo: dropped.
        Map<String, Object> echoOnly = agent.extractSlots(bookingEntity, "Booking", "vi-VN", conversation,
                "hôm nay");
        assertFalse(echoOnly.containsKey("customerName"));
    }

    @Test
    void groundingIgnoresCaseDiacriticsAndWhitespace() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn B\",\"customerEmail\":\"b@example.com\"}}",
                "vylinh", 100, 10
        ));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(),
                "dat lich cho  NGUYEN van b, mail B@Example.com");

        assertEquals("Nguyễn Văn B", slots.get("customerName"));
        assertEquals("b@example.com", slots.get("customerEmail"));
    }

    @Test
    void valuesGivenAcrossSeveralUserTurnsStayGrounded() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn B\",\"customerEmail\":\"b@example.com\","
                + "\"bookingDate\":\"2026-11-07\"}}",
                "vylinh", 100, 10
        ));
        List<ChatMessage> conversation = List.of(
                message("user", "Đặt lịch cho Nguyễn Văn B"),
                message("assistant", "Vui lòng cho tôi email khách và thời gian hẹn."),
                message("user", "email là b@example.com"),
                message("assistant", "Bạn muốn hẹn khi nào?"));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", conversation, "ngày mai");

        assertEquals("Nguyễn Văn B", slots.get("customerName"));
        assertEquals("b@example.com", slots.get("customerEmail"));
    }

    @Test
    void phoneNumbersMatchRegardlessOfSpacingAndPunctuation() {
        EntityDetail leads = new EntityDetail("leads", "Khách hàng", List.of(
                new FieldDetail("name", "string", true, "Tên"),
                new FieldDetail("phone", "string", false, "Điện thoại")));
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"name\":\"Nguyễn Văn An\",\"phone\":\"0912345678\"}}", "vylinh", 100, 10));

        Map<String, Object> spaced = agent.extractSlots(leads, "CRM", "vi-VN", List.of(),
                "Thêm khách Nguyễn Văn An, sđt 0912 345 678");
        assertEquals("0912345678", spaced.get("phone"));

        Map<String, Object> wrongNumber = agent.extractSlots(leads, "CRM", "vi-VN", List.of(),
                "Thêm khách Nguyễn Văn An, sđt 0987 654 321");
        assertFalse(wrongNumber.containsKey("phone"), "a number the user did not type must be dropped");
    }

    @Test
    void aNameIsNotGroundedByAnotherNameItIsAPrefixOf() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\"}}", "vylinh", 100, 10));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(),
                "Tạo lịch với Nguyễn Văn An hôm nay");

        assertFalse(slots.containsKey("customerName"), "\"Nguyễn Văn A\" must not match inside \"Nguyễn Văn An\"");
    }

    @Test
    void formsOfAddressAreNotPartOfTheStoredName() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"anh Trường Giang\"}}", "vylinh", 100, 10));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(),
                "Ừ nhầm anh Trường Giang, giang@example.com");

        assertEquals("Trường Giang", slots.get("customerName"));
    }

    @Test
    void honorificStrippingKeepsGivenNamesThatLookLikeOne() {
        assertEquals("Trấn Thành", BizAppIntentRouter.stripHonorific("anh Trấn Thành"));
        assertEquals("Lan", BizAppIntentRouter.stripHonorific("chị Lan"));
        assertEquals("Nguyễn Văn B", BizAppIntentRouter.stripHonorific("Mr. Nguyễn Văn B"));
        assertEquals("Anh Thư", BizAppIntentRouter.stripHonorific("Anh Thư"), "capitalized given name is a name");
        assertEquals("anh", BizAppIntentRouter.stripHonorific("anh"), "nothing follows, nothing to strip");
        assertEquals("Trấn Thành", BizAppIntentRouter.cleanNameValue("tên khách là anh Trấn Thành"));
    }

    @Test
    void extractSlotsReturnsEmptyMapOnLlmFailure() {
        when(aiInferenceApi.completeJson(any())).thenThrow(new IllegalStateException("model unavailable"));

        Map<String, Object> slots = agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(), "");

        assertTrue(slots.isEmpty(), "router must degrade to deterministic extraction");
        verify(aiInferenceApi, times(1)).completeJson(any());
    }

    @Test
    void extractSlotsReturnsEmptyMapOnBlankOutput() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse("{}", "vylinh", 100, 2));

        assertTrue(agent.extractSlots(bookingEntity, "Booking", "vi-VN", List.of(), "").isEmpty());
    }

    @Test
    void jsonSchemaIsGeneratedFromManifestFields() throws Exception {
        String schema = agent.buildJsonSchema(bookingEntity);
        JsonNode root = objectMapper.readTree(schema);

        JsonNode fieldsProps = root.path("properties").path("fields").path("properties");
        assertTrue(fieldsProps.has("customerName"));
        assertTrue(fieldsProps.has("customerEmail"));
        assertTrue(fieldsProps.has("service"));
        assertTrue(fieldsProps.has("bookingDate"));
        assertTrue(fieldsProps.has("guests"));
        assertEquals("number", fieldsProps.path("guests").path("type").asText());
        assertEquals(3, fieldsProps.path("service").path("enum").size());
        assertTrue(root.path("properties").path("fields").path("required").isMissingNode(),
                "all slots optional at grammar level; required enforcement stays deterministic");
    }

    @Test
    void systemPromptIsBuiltOnlyFromManifestContract() {
        // Capture the actual request the agent sends
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{}}", "vylinh", 100, 2
        ));
        agent.extractSlots(bookingEntity, "Booking", "vi-VN",
                List.of(message("user", "Bạn đặt lịch giúp tôi")), "Nguyễn Văn A, hôm nay, lúc 21h22");

        ArgumentCaptor<StructuredJsonRequest> captor = ArgumentCaptor.forClass(StructuredJsonRequest.class);
        verify(aiInferenceApi).completeJson(captor.capture());
        StructuredJsonRequest sent = captor.getValue();

        String systemPrompt = sent.systemPrompt();
        // Manifest-driven, no hardcoded per-app content:
        assertTrue(systemPrompt.contains("\"customerName\""));
        assertTrue(systemPrompt.contains("Thời gian hẹn"));
        assertTrue(systemPrompt.contains("consultation"));
        assertTrue(systemPrompt.contains("phòng họp"));
        assertTrue(systemPrompt.contains("(bắt buộc)"));
        assertTrue(systemPrompt.contains("user: Bạn đặt lịch giúp tôi"));
        assertTrue(systemPrompt.contains("user: Nguyễn Văn A, hôm nay, lúc 21h22"));
        // Inline schema (not a legacy file name):
        assertTrue(sent.jsonSchema().startsWith("{"));
    }
}
