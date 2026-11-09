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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonResponse;
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.service.BizAppSlotAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies W7.4 with the LLM slot agent enabled: a free-form message such as
 * "Nguyễn Văn A, hôm nay, lúc 21h22" fills the manifest slots the deterministic
 * extractor cannot see (leading person name, combined date+time), while the
 * reply/ card building and required-field validation stay deterministic.
 */
class RecordSlotLlmExtractionTest {

    private BizAppRuntimeApi runtimeApi;
    private AiInferenceApi aiInferenceApi;
    private BizAppIntentRouter router;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private ChatMessage userMessage(String content) {
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent(content);
        return msg;
    }

    @BeforeEach
    void setUp() {
        runtimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        BizAppSlotAgent slotAgent = new BizAppSlotAgent(aiInferenceApi, new ObjectMapper());
        slotAgent.setSlotMode("llm");
        router = new BizAppIntentRouter(runtimeApi, aiInferenceApi, new ObjectMapper(), slotAgent);
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(true);

        EntityDetail bookings = new EntityDetail(
                "bookings", "Lịch hẹn",
                List.of(
                        new FieldDetail("customerName", "string", true, "Tên khách"),
                        new FieldDetail("customerEmail", "string", true, "Email khách", List.of(), "email"),
                        new FieldDetail("service", "enum", true, "Dịch vụ",
                                List.of("consultation", "tour", "meeting-room")),
                        new FieldDetail("bookingDate", "date", true, "Thời gian hẹn")
                )
        );

        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "App đặt lịch",
                List.of("bookings"), List.of(), "ACTIVE", List.of(bookings),
                List.of("lập lịch", "đặt lịch", "hẹn lịch", "tạo lịch", "cuộc hẹn", "lịch hẹn", "booking")
        );

        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
    }

    @Test
    void llmSlotsFillNameAndCombineDateWithTimeFromFreeFormMessage() {
        // Prior turn establishes the app context (conversation lookback).
        List<ChatMessage> prior = List.of(userMessage("Bạn có thể đặt lịch cho tôi không?"));

        // Slot agent (completeJson) returns manifest-slots for the free-form message.
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\",\"bookingDate\":\"2026-11-05T21:22\"}}",
                "vylinh", 120, 24
        ));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Nguyễn Văn A, hôm nay, lúc 21h22", "vi-VN", sessionId, prior);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertNotNull(result.actionCard());
        assertEquals("INVALID", result.actionCard().get("status"),
                "only name+date/time known: email & service still required");

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertEquals("Nguyễn Văn A", fields.get("customerName"));
        assertEquals("2026-11-05T21:22", fields.get("bookingDate"),
                "explicit time must survive and merge into the combined date field");
        assertFalse(fields.containsKey("time"), "no stray raw keys in the card");

        String reply = result.reply();
        assertTrue(reply.contains("Nguyễn Văn A"));
        assertTrue(reply.contains("Email khách"));
        assertTrue(reply.contains("Dịch vụ"));
    }

    @Test
    void completeSlotsProduceProposedCardWithoutAdditionalPrompt() {
        List<ChatMessage> prior = List.of(userMessage("Bạn có thể đặt lịch cho tôi không?"));

        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\",\"customerEmail\":\"an@example.com\","
                + "\"service\":\"meeting-room\",\"bookingDate\":\"2026-11-05T21:22\"}}",
                "vylinh", 120, 40
        ));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Nguyễn Văn A, phòng họp, an@example.com, hôm nay 21h22", "vi-VN", sessionId, prior);

        assertEquals("PROPOSED", result.actionCard().get("status"));
        assertNotNull(result.actionCard().get("payloadHash"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertEquals("meeting-room", fields.get("service"));
        assertEquals("an@example.com", fields.get("customerEmail"));
    }

    @Test
    void hallucinatedNameAndEmailNeverReachTheActionCard() {
        List<ChatMessage> prior = List.of(userMessage("Bạn có thể đặt lịch cho tôi không?"));

        // The user names "Nguyễn Văn B" and gives no email; the model answers with invented values.
        when(aiInferenceApi.completeJson(any())).thenReturn(new StructuredJsonResponse(
                "{\"fields\":{\"customerName\":\"Nguyễn Văn A\",\"customerEmail\":\"a@example.com\","
                + "\"service\":\"consultation\",\"bookingDate\":\"2026-11-05\"}}",
                "vylinh", 120, 40
        ));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Tạo lịch với Nguyễn Văn B hôm nay, dịch vụ là tư vấn", "vi-VN", sessionId, prior);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertNotEquals("Nguyễn Văn A", fields.get("customerName"));
        assertFalse(fields.containsKey("customerEmail"), "no invented email on the card");
        assertEquals("INVALID", result.actionCard().get("status"),
                "name/email are missing, so the router must ask instead of proposing");
    }

    @Test
    void llmFailureDegradesToDeterministicExtraction() {
        List<ChatMessage> prior = List.of(userMessage("Bạn có thể đặt lịch cho tôi không?"));

        when(aiInferenceApi.completeJson(any())).thenThrow(new IllegalStateException("model down"));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Nguyễn Văn A, hôm nay, lúc 21h22", "vi-VN", sessionId, prior);

        // Degraded path == pre-agent behavior: date extracted, name/time lost,
        // required-field prompt remains valid (no crash, no fabricated slots).
        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("INVALID", result.actionCard().get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertEquals(LocalDate.now().toString(), fields.get("bookingDate"));
        assertFalse(fields.containsKey("customerName"));
    }
}
