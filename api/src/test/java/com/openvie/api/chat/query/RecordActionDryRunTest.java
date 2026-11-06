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
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.chat.service.BizAppSlotAgent;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppActionOffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies Task W7.3: Dry-run validation and record action card generation.
 */
class RecordActionDryRunTest {

    private BizAppRuntimeApi runtimeApi;
    private AiInferenceApi aiInferenceApi;
    private BizAppIntentRouter router;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runtimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        router = new BizAppIntentRouter(runtimeApi, aiInferenceApi, new ObjectMapper(),
                new BizAppSlotAgent(aiInferenceApi, new ObjectMapper()));
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(true);

        EntityDetail leads = new EntityDetail(
                "leads", "Khách hàng tiềm năng",
                List.of(
                        new FieldDetail("name", "string", true, "Tên khách hàng"),
                        new FieldDetail("phone", "string", false, "Số điện thoại"),
                        new FieldDetail("email", "string", false, "Email")
                )
        );

        InstalledAppSummary crm = new InstalledAppSummary(
                UUID.randomUUID(), "crm", "CRM Lite", 1, "Quản lý khách hàng",
                List.of("leads"), List.of(), "ACTIVE", List.of(leads)
        );

        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(crm));
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
    }

    @Test
    void completePromptGeneratesProposedRecordCardWithPayloadHash() {
        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm khách hàng tên Nguyễn Văn An, số điện thoại 0912345678, email an@example.com vào CRM",
                "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertNotNull(result.actionCard());
        Map<String, Object> card = result.actionCard();
        assertEquals("PROPOSED", card.get("status"));
        assertEquals("bizapp.record.create", card.get("kind"));
        assertEquals("crm", card.get("appKind"));
        assertEquals("leads", card.get("entity"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) card.get("fields");
        assertNotNull(fields);
        assertEquals("Nguyễn Văn An", fields.get("name"));
        assertEquals("0912345678", fields.get("phone"));
        assertEquals("an@example.com", fields.get("email"));

        assertNotNull(card.get("payloadHash"));
        assertTrue(String.valueOf(card.get("payloadHash")).startsWith("sha256:"));
    }

    @Test
    void missingRequiredFieldGeneratesInvalidRecordCardWithoutHash() {
        // Query without name (required field)
        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm vào CRM số điện thoại 0912345678",
                "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertNotNull(result.actionCard());

        Map<String, Object> card = result.actionCard();
        assertEquals("INVALID", card.get("status"), "Card must be marked INVALID when required fields are missing");
        assertNull(card.get("payloadHash"), "Invalid card must not have a payload hash");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> invalid = (List<Map<String, String>>) card.get("invalid");
        assertNotNull(invalid);
        assertFalse(invalid.isEmpty());
        assertEquals("name", invalid.get(0).get("field"));
        assertEquals("REQUIRED", invalid.get(0).get("code"));
    }

    @Test
    void askingToCreateRecordWhenAppNotInstalledOffersToInstallIt() {
        // Booking is NOT installed, but available in catalog
        MarketplaceTemplateSummary bookingTmpl = new MarketplaceTemplateSummary(
                "booking", "Đặt lịch hẹn", 1, "App đặt lịch", List.of("bookings"), List.of(), "platform:booking",
                List.of(new EntityDetail("bookings", "Lịch hẹn", List.of()))
        );
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of(bookingTmpl));
        when(runtimeApi.offerInstall(tenantId, orgId, "booking", actorId)).thenReturn(
                new AppActionOffer(UUID.randomUUID(), "bizapp.install", "booking", "Booking", 1, "App đặt lịch", "ORG_OWNER", "platform:booking")
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể tạo lịch hẹn cho tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.INSTALL, result.intent());
        assertEquals("booking", result.appKind());
        assertNotNull(result.actionCard());
        assertEquals("PROPOSED", result.actionCard().get("status"));
        assertEquals("bizapp.install", result.actionCard().get("kind"));
        assertTrue(result.reply().contains("Đặt lịch hẹn") || result.reply().contains("Booking"));
    }

    @Test
    void askingToCreateRecordWhenAppIsInstalledPromptsForDetails() {
        EntityDetail appointments = new EntityDetail(
                "bookings", "Lịch hẹn",
                List.of(new FieldDetail("title", "string", true, "Tiêu đề cuộc hẹn"))
        );
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "App đặt lịch",
                List.of("bookings"), List.of(), "ACTIVE", List.of(appointments)
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể tạo lịch hẹn cho tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("booking", result.appKind());
        assertNull(result.actionCard(), "Should not create action card without any fields");
        assertTrue(result.reply().contains("thông tin chi tiết") || result.reply().contains("details"));
    }

    @Test
    void multiTurnContextLookbackMaintainsAppContextAcrossFillerTurns() {
        EntityDetail appointments = new EntityDetail(
                "bookings", "Lịch hẹn",
                List.of(
                        new FieldDetail("customerName", "string", true, "Tên khách"),
                        new FieldDetail("bookingDate", "string", true, "Thời gian"),
                        new FieldDetail("service", "enum", false, "Dịch vụ")
                )
        );
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "Đặt lịch hẹn",
                List.of("bookings"), List.of(), "ACTIVE", List.of(appointments)
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));

        com.openvie.api.chat.model.ChatMessage turn1User = new com.openvie.api.chat.model.ChatMessage();
        turn1User.setRole("user");
        turn1User.setContent("Bạn có thể giúp tôi tạo lịch không");

        com.openvie.api.chat.model.ChatMessage turn1Bot = new com.openvie.api.chat.model.ChatMessage();
        turn1Bot.setRole("assistant");
        turn1Bot.setContent("Tôi có thể giúp bạn tạo lịch hẹn trong Booking. Vui lòng cho tôi biết thông tin chi tiết nhé!");

        // Turn 2: User filler turn ("Chờ tôi tý tôi đi lấy thông tin đã")
        var turn2Result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Chờ tôi tý tôi đi lấy thông tin đã", "vi-VN", sessionId, List.of(turn1User, turn1Bot));
        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, turn2Result.intent(), "Filler turn must stay in app context");
        assertEquals("booking", turn2Result.appKind());
        assertNull(turn2Result.actionCard());
        assertTrue(turn2Result.reply().contains("chuẩn bị thông tin"));

        com.openvie.api.chat.model.ChatMessage turn2User = new com.openvie.api.chat.model.ChatMessage();
        turn2User.setRole("user");
        turn2User.setContent("Chờ tôi tý tôi đi lấy thông tin đã");

        com.openvie.api.chat.model.ChatMessage turn2Bot = new com.openvie.api.chat.model.ChatMessage();
        turn2Bot.setRole("assistant");
        turn2Bot.setContent(turn2Result.reply());

        // Turn 3: User replies with time and name ("Hôm nay 13h45, Nguyễn Văn A")
        var turn3Result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Hôm nay 13h45, Nguyễn Văn A", "vi-VN", sessionId, List.of(turn1User, turn1Bot, turn2User, turn2Bot));

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, turn3Result.intent(), "Turn 3 must stay in app context without going to RAG");
        assertEquals("booking", turn3Result.appKind());
        assertNotNull(turn3Result.actionCard(), "Both required fields present => generates action card");

        Map<String, Object> card = turn3Result.actionCard();
        assertEquals("PROPOSED", card.get("status"));
        assertEquals("bizapp.record.create", card.get("kind"));
        assertEquals("booking", card.get("appKind"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) card.get("fields");
        assertEquals("Nguyễn Văn A", fields.get("customerName"));
        assertEquals(java.time.LocalDate.now().toString(), fields.get("bookingDate"));
    }

    @Test
    void enumValidationReportsInvalidOptionAndAliasMatchesValidOption() {
        EntityDetail appointments = new EntityDetail(
                "bookings", "Lịch hẹn",
                List.of(
                        new FieldDetail("customerName", "string", true, "Tên khách"),
                        new FieldDetail(
                                "service", "enum", true, "Dịch vụ",
                                List.of("consultation", "tour", "meeting-room"),
                                null,
                                Map.of("consultation", "Tư vấn", "tour", "Tham quan", "meeting-room", "Phòng họp"),
                                Map.of("consultation", List.of("tư vấn", "tu van"), "tour", List.of("tham quan"), "meeting-room", List.of("phòng họp"))
                        ),
                        new FieldDetail("bookingDate", "string", true, "Thời gian")
                )
        );
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "Đặt lịch hẹn",
                List.of("bookings"), List.of(), "ACTIVE", List.of(appointments)
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));

        // 1. User says "khách là Nguyễn Văn A, Service là xây dựng, hôm nay 14h"
        // Name must be cleaned to "Nguyễn Văn A" (no "khách là")
        // "xây dựng" is not in ["consultation", "tour", "meeting-room"] => INVALID card with helpful prompt
        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Tạo lịch hẹn cho khách là Nguyễn Văn A, Service là xây dựng, hôm nay 14h", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertNotNull(result.actionCard());
        assertEquals("INVALID", result.actionCard().get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertEquals("Nguyễn Văn A", fields.get("customerName"), "Name must be stripped of 'khách là' prefix");
        assertFalse(fields.containsKey("name"), "Extraneous 'name' key must not be present when entity has customerName");
        assertFalse(fields.containsKey("customerEmail"), "Unspecified optional fields must not be injected");

        // Reply must guide user about valid options
        assertTrue(result.reply().contains("consultation") || result.reply().contains("Dịch vụ"));

        // 2. User says "Dịch vụ là tư vấn" => maps to "consultation"
        var validResult = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Tạo lịch hẹn cho khách là Nguyễn Văn A, Dịch vụ là tư vấn, hôm nay 14h", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, validResult.intent());
        assertNotNull(validResult.actionCard());
        assertEquals("PROPOSED", validResult.actionCard().get("status"), "Valid enum must produce PROPOSED card");

        @SuppressWarnings("unchecked")
        Map<String, Object> validFields = (Map<String, Object>) validResult.actionCard().get("fields");
        assertEquals("consultation", validFields.get("service"), "'tư vấn' must map to 'consultation'");
        assertEquals("Nguyễn Văn A", validFields.get("customerName"));
        assertEquals(java.time.LocalDate.now().toString(), validFields.get("bookingDate"));
    }

    @Test
    void lapLichVoiKhachHangRoutesToBookingWithoutRAG() {
        EntityDetail appointments = new EntityDetail(
                "bookings", "Lịch hẹn",
                List.of(
                        new FieldDetail("customerName", "string", true, "Tên khách"),
                        new FieldDetail("service", "enum", true, "Dịch vụ", List.of("consultation", "tour", "meeting-room")),
                        new FieldDetail("bookingDate", "string", true, "Thời gian")
                )
        );
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "Đặt lịch hẹn",
                List.of("bookings"), List.of(), "ACTIVE", List.of(appointments),
                List.of("lập lịch", "đặt lịch", "hẹn lịch")
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));

        // User prompt: "Bạn có thể lập lịch với khách hàng giúp tôi không?"
        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể lập lịch với khách hàng giúp tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent(),
                "Must route to APP_RECORD_CREATE for booking, never to document RAG (NONE)");
        assertEquals("booking", result.appKind());
        assertNull(result.actionCard(), "General capability question without fields should not produce a card yet");
        assertTrue(result.reply().contains("Booking") && result.reply().contains("thông tin chi tiết"),
                "Must prompt for details conversationally");
    }

    @Test
    void relativeDateAndTimeToIsoResolutionTest() {
        EntityDetail leads = new EntityDetail(
                "leads", "Khách hàng",
                List.of(
                        new FieldDetail("name", "string", true, "Tên khách hàng"),
                        new FieldDetail("visit_date", "date", false, "Ngày hẹn"),
                        new FieldDetail("visit_time", "string", false, "Giờ hẹn"),
                        new FieldDetail("notes", "string", false, "Ghi chú")
                )
        );
        InstalledAppSummary crm = new InstalledAppSummary(
                UUID.randomUUID(), "crm", "CRM Lite", 1, "Quản lý khách hàng",
                List.of("leads"), List.of(), "ACTIVE", List.of(leads),
                List.of("khách hàng", "lead")
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(crm));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm khách hàng Nguyễn Văn A, hôm nay 17h20, ghi chú thăm văn phòng", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertNotNull(result.actionCard());

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertEquals("Nguyễn Văn A", fields.get("name"));
        assertEquals(java.time.LocalDate.now().toString(), fields.get("visit_date"), "'hôm nay' must resolve to actual ISO date");
        assertEquals("17:20", fields.get("visit_time"), "'17h20' must resolve to '17:20'");
        assertEquals("thăm văn phòng", fields.get("notes"));
    }

    @Test
    void dayOfWeekAndNextWeekResolutionTest() {
        EntityDetail appointments = new EntityDetail(
                "appointments", "Lịch hẹn",
                List.of(
                        new FieldDetail("customerName", "string", true, "Tên khách", null, null),
                        new FieldDetail("bookingDate", "date", true, "Ngày hẹn", null, null),
                        new FieldDetail("bookingTime", "string", false, "Giờ hẹn", null, null)
                )
        );
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "Đặt lịch hẹn",
                List.of("appointments"), List.of(), "ACTIVE", List.of(appointments),
                List.of("đặt lịch", "lịch hẹn", "hẹn lịch", "booking")
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));

        java.time.LocalDate today = java.time.LocalDate.now();
        int currentDow = today.getDayOfWeek().getValue();
        java.time.LocalDate mondayNextWeek = today.minusDays(currentDow - 1).plusDays(7);
        java.time.LocalDate nextTuesday = mondayNextWeek.plusDays(1); // Tuesday = Monday + 1

        // 1. Vietnamese "thứ 3 tuần sau 14h"
        var resVi = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Lập lịch hẹn khách hàng Trần Bình, thứ 3 tuần sau 14h", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, resVi.intent());
        assertNotNull(resVi.actionCard());
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldsVi = (Map<String, Object>) resVi.actionCard().get("fields");
        assertEquals("Trần Bình", fieldsVi.get("customerName"));
        assertEquals(nextTuesday.toString(), fieldsVi.get("bookingDate"), "'thứ 3 tuần sau' must resolve to next Tuesday ISO date");
        assertEquals("14:00", fieldsVi.get("bookingTime"), "'14h' must resolve to '14:00'");

        // 2. English "next Tuesday 2pm"
        var resEn = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Schedule booking customer John Doe, next Tuesday 2pm", "en-US", sessionId);
        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, resEn.intent());
        assertNotNull(resEn.actionCard());
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldsEn = (Map<String, Object>) resEn.actionCard().get("fields");
        assertEquals("John Doe", fieldsEn.get("customerName"));
        assertEquals(nextTuesday.toString(), fieldsEn.get("bookingDate"), "'next Tuesday' must resolve to next Tuesday ISO date");
        assertEquals("14:00", fieldsEn.get("bookingTime"), "'2pm' must resolve to '14:00'");
    }
}
