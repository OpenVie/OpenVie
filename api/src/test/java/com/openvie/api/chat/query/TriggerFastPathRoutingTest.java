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
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonRequest;
import com.openvie.api.ai.api.AiInferenceApi.StructuredJsonResponse;
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import com.openvie.api.chat.service.BizAppSlotAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies fresh-session routing for manifest trigger requests: a polite
 * "Bạn có thể lên lịch cho tôi không?" (previously fell through to RAG because
 * the small intent model answered NONE) now resolves deterministically through
 * the app's manifest triggers, while information questions and procedures keep
 * their RAG/detail semantics. The LLM classifier prompt carries the
 * deterministic trigger match as a HIGH TRUST hint.
 */
class TriggerFastPathRoutingTest {

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
        BizAppSlotAgent slotAgent = new BizAppSlotAgent(aiInferenceApi, new ObjectMapper());
        slotAgent.setSlotMode("regex"); // isolate routing behavior from slot extraction
        router = new BizAppIntentRouter(runtimeApi, aiInferenceApi, new ObjectMapper(), slotAgent);
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(true);
    }

    private void withInstalledBooking() {
        EntityDetail bookings = new EntityDetail(
                "bookings", "Lịch hẹn",
                List.of(new FieldDetail("customerName", "string", true, "Tên khách"))
        );
        InstalledAppSummary booking = new InstalledAppSummary(
                UUID.randomUUID(), "booking", "Booking", 1, "App đặt lịch",
                List.of("bookings"), List.of(), "ACTIVE", List.of(bookings),
                List.of("lập lịch", "đặt lịch", "lên lịch", "cuộc hẹn", "lịch hẹn", "booking")
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
    }

    private void withCatalogOnlyBooking() {
        MarketplaceTemplateSummary booking = new MarketplaceTemplateSummary(
                "booking", "Booking", 1, "App đặt lịch",
                List.of(), List.of(), "platform:booking", List.of(),
                List.of("lập lịch", "đặt lịch", "lên lịch", "cuộc hẹn", "lịch hẹn", "booking")
        );
        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of(booking));

        when(runtimeApi.offerInstall(tenantId, orgId, "booking", actorId)).thenReturn(
                new BizAppRuntimeApi.AppActionOffer(
                        UUID.randomUUID(), "bizapp.install", "booking", "Booking", 1,
                        "App đặt lịch", "ORG_OWNER", "platform:booking")
        );
    }

    @Test
    void freshSessionTriggerPoliteRequestShortCircuitsDeterministicallyWithoutLlm() {
        withInstalledBooking();

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể lên lịch cho tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("booking", result.appKind());
        assertTrue(result.reply().contains("Booking") && result.reply().contains("thông tin chi tiết"),
                "falls back to the prompt-for-details reply, same as explicit write requests");
        verify(aiInferenceApi, never()).completeJson(any());
    }

    @Test
    void freshSessionTriggerRequestForUninstalledAppPromptsInstall() {
        withCatalogOnlyBooking();

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể lên lịch cho tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.INSTALL, result.intent());
        assertEquals("booking", result.appKind());
        assertNotNull(result.offer());
        assertTrue(result.reply().contains("chưa được cài đặt"));
        verify(aiInferenceApi, never()).completeJson(any());
    }

    @Test
    void informationalQuestionKeepsClassifierSemanticsAndCarriesHint() {
        withInstalledBooking();

        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse(
                        "{\"intent\":\"APP_DETAIL\",\"appKind\":\"booking\",\"confidence\":0.9,"
                        + "\"reply\":\"Chi tiết ứng dụng Booking\"}", "vylinh", 64, 16)
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Booking là gì?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_DETAIL, result.intent());

        ArgumentCaptor<StructuredJsonRequest> captor = ArgumentCaptor.forClass(StructuredJsonRequest.class);
        verify(aiInferenceApi).completeJson(captor.capture());
        String systemPrompt = captor.getValue().systemPrompt();
        assertTrue(systemPrompt.contains("Deterministic trigger match (HIGH TRUST)"),
                "classifier must receive the deterministic trigger-match hint");
        assertTrue(systemPrompt.contains("'booking'"));
        assertTrue(systemPrompt.contains(
                "NEVER return INSTALL for an app already listed under Installed Apps"));
        assertTrue(systemPrompt.contains("Gỡ ứng dụng Booking"),
                "classifier must carry the manifest-generated uninstall few-shot");
        assertTrue(systemPrompt.contains("Thêm Lịch hẹn mới"),
                "classifier must carry the manifest-generated record few-shot");
    }

    @Test
    void procedureQuestionsStillRouteToRagBeforeClassifier() {
        withInstalledBooking();

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Quy trình đặt lịch trong Booking gồm mấy bước?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent(), "procedures stay in RAG");
        assertNull(result.actionCard());
        verify(aiInferenceApi, never()).completeJson(any());
    }

    @Test
    void explicitWriteVerbsStillShortCircuitBeforeTriggers() {
        withInstalledBooking();

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm booking cho khách Nguyễn Văn An", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("booking", result.appKind());
        verify(aiInferenceApi, never()).completeJson(any());
    }

    @Test
    void uninstallAppPhrasingStandsDownFromRecordFastPathToClassifier() {
        withInstalledBooking();
        when(runtimeApi.offerUninstall(tenantId, "booking", actorId)).thenReturn(
                new BizAppRuntimeApi.AppActionOffer(
                        UUID.randomUUID(), "bizapp.uninstall", "booking", "Booking", 1,
                        "App đặt lịch", "ORG_OWNER", null));
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse(
                        "{\"intent\":\"UNINSTALL\",\"appKind\":\"booking\",\"confidence\":0.95,"
                        + "\"reply\":\"Gỡ ứng dụng Booking\"}", "vylinh", 64, 16)
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Gỡ ứng dụng đặt lịch giúp tôi.", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.UNINSTALL, result.intent());
        assertEquals("booking", result.appKind());
        assertNotNull(result.offer(), "route must build the uninstall offer card");
        assertEquals("bizapp.uninstall", result.offer().kind());
        assertNotNull(result.actionCard());
        assertEquals("bizapp.uninstall", result.actionCard().get("kind"));
        verify(aiInferenceApi).completeJson(any());
    }

    @Test
    void installAppPhrasingForCatalogAppKeepsDeterministicInstallPrompt() {
        withCatalogOnlyBooking();

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Cài đặt app đặt lịch cho tôi.", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.INSTALL, result.intent());
        assertEquals("booking", result.appKind());
        assertNotNull(result.offer());
        assertTrue(result.reply().contains("chưa được cài đặt"));
        verify(aiInferenceApi, never()).completeJson(any());
    }
}
