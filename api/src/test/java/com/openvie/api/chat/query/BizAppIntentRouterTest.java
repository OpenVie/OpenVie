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
import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppActionOffer;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.service.BizAppSlotAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BizAppIntentRouterTest {

    private BizAppRuntimeApi bizAppRuntimeApi;
    private AiInferenceApi aiInferenceApi;
    private ObjectMapper objectMapper;
    private BizAppIntentRouter router;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bizAppRuntimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        objectMapper = new ObjectMapper();

        router = new BizAppIntentRouter(bizAppRuntimeApi, aiInferenceApi, objectMapper,
                new BizAppSlotAgent(aiInferenceApi, objectMapper));
        router.setActionsEnabled(true);
        router.setConfidenceThreshold(0.75);

        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(
                new InstalledAppSummary(UUID.randomUUID(), "crm", "CRM", 1, "", List.of(), List.of(), "ACTIVE")
        ));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of(
                new MarketplaceTemplateSummary("booking", "Booking", 1, "", List.of(), List.of(), "platform:booking")
        ));
    }

    @Test
    void deterministicGateMatchesVietnameseAndEnglishKeywords() {
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("hãy cài app đặt lịch"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("cai dat ung dung crm"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("please install booking app"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("danh sach app"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("list apps"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("gỡ cài đặt crm"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("xem cac ban ghi trong ung dung"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Bạn có thể có cách nào quản lý thiết bị IT cho tôi không?"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Làm sao để theo dõi tài sản công ty?"));
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("Tôi muốn gửi yêu cầu."));

        // General documents / conversation without admin verbs or app vocab
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("xin chào, bạn là ai?"));
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("hợp đồng lao động mẫu năm 2026"));
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("chính sách nghỉ phép của công ty"));
    }

    @Test
    void installedAppWithoutTriggersMatchesNaturalNameWords() {
        InstalledAppSummary inventory = new InstalledAppSummary(
                UUID.randomUUID(),
                "inventory",
                "Yêu cầu Mua sắm",
                1,
                "",
                List.of("shopping_requests"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "shopping_requests",
                        "Yêu cầu Mua sắm",
                        List.of(
                                new FieldDetail("item_name", "string", true, "Món hàng"),
                                new FieldDetail("amount", "number", true, "Số tiền")
                        )
                )),
                List.of()
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(inventory));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Tôi muốn viết một phiếu xin mua sắm", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("inventory", result.appKind());
        assertTrue(result.reply().contains("Yêu cầu Mua sắm"));
        assertTrue(result.reply().contains("Món hàng"), "the first prompt must include the field list");
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void pendingRecordDetailsReturnsFieldsWithoutAnotherRegex() {
        InstalledAppSummary inventory = new InstalledAppSummary(
                UUID.randomUUID(),
                "inventory",
                "Yêu cầu Mua sắm",
                1,
                "",
                List.of("shopping_requests"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "shopping_requests",
                        "Yêu cầu Mua sắm",
                        List.of(
                                new FieldDetail("item_name", "string", true, "Món hàng"),
                                new FieldDetail("amount", "number", true, "Số tiền")
                        )
                )),
                List.of()
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(inventory));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "inventory", new BizAppRuntimeApi.ActionCapabilities(
                        true, true, true, false, true, false)));

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Cho tôi biết những thông tin cần điền nhé.", "vi-VN", sessionId, List.of(),
                Map.of("bizappPending", Map.of(
                        "appKind", "inventory",
                        "entity", "shopping_requests")));

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("inventory", result.appKind());
        assertEquals("shopping_requests", result.entity());
        assertEquals("Field information request in active app context", result.reason());
        assertTrue(result.reply().contains("Món hàng"));
        assertTrue(result.reply().contains("Số tiền"));
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingDetailsUsesManifestSlotAgentForFreeFormValues() {
        InstalledAppSummary inventory = new InstalledAppSummary(
                UUID.randomUUID(),
                "inventory",
                "Yêu cầu Mua sắm",
                1,
                "",
                List.of("shopping_requests"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "shopping_requests",
                        "Yêu cầu Mua sắm",
                        List.of(
                                new FieldDetail("item_name", "string", true, "Món hàng"),
                                new FieldDetail("amount", "number", true, "Số tiền")
                        )
                )),
                List.of()
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(inventory));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "inventory", new BizAppRuntimeApi.ActionCapabilities(
                        true, true, true, false, true, false)));
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse("{\"fields\":{\"item_name\":\"bút\"}}", "vylinh", 20, 10));

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Tôi muốn mua một cây bút", "vi-VN", sessionId, List.of(),
                Map.of("bizappPending", Map.of(
                        "appKind", "inventory",
                        "entity", "shopping_requests")));

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("inventory", result.appKind());
        assertNotNull(result.actionCard());
        Map<String, Object> fields = (Map<String, Object>) result.actionCard().get("fields");
        assertEquals("bút", fields.get("item_name"));
        verify(aiInferenceApi, atLeastOnce()).completeJson(any(StructuredJsonRequest.class));
    }

    @Test
    void installedAppFollowUpUsesContextAndMemberWriteGrant() {
        InstalledAppSummary inventory = new InstalledAppSummary(
                UUID.randomUUID(),
                "inventory",
                "Yêu cầu Mua sắm",
                1,
                "",
                List.of("shopping_requests"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "shopping_requests",
                        "Yêu cầu Mua sắm",
                        List.of(
                                new FieldDetail("item_name", "string", true, "Món hàng"),
                                new FieldDetail("amount", "number", true, "Số tiền")
                        )
                )),
                List.of()
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(inventory));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "inventory", new BizAppRuntimeApi.ActionCapabilities(
                        true, true, true, false, true, false)));

        List<ChatMessage> prior = List.of(
                message("user", "Tôi muốn viết một phiếu xin mua sắm"),
                message("assistant", "Tôi hiểu bạn muốn tạo một yêu cầu mua sắm.")
        );
        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Tôi muốn xin mua cái này", "vi-VN", sessionId, prior);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("inventory", result.appKind());
        assertFalse(result.reply().contains("không được phép"));
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void semanticSelectorRoutesAnyInstalledAppWithoutAnExplicitAlias() {
        InstalledAppSummary ticketing = new InstalledAppSummary(
                UUID.randomUUID(),
                "ticketing",
                "Ticketing",
                1,
                "Gửi và theo dõi phiếu yêu cầu nội bộ",
                List.of("tickets"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "tickets",
                        "Phiếu yêu cầu",
                        List.of(new FieldDetail("requester", "string", true, "Người gửi"))
                )),
                List.of()
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(ticketing));
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenAnswer(invocation -> {
            StructuredJsonRequest request = invocation.getArgument(0);
            if (request.systemPrompt().contains("OpenVie's app router")) {
                return new StructuredJsonResponse(
                        "{\"appKind\":\"ticketing\",\"entity\":\"tickets\",\"confidence\":0.9}",
                        "vylinh", 80, 20);
            }
            throw new AssertionError("Classifier should not run for a semantically matched create request");
        });

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Tôi muốn gửi một yêu cầu nội bộ.", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("ticketing", result.appKind());
        assertEquals("tickets", result.entity());
    }

    @Test
    void fieldInfoFollowUpStaysInActiveAppContext() {
        InstalledAppSummary leave = new InstalledAppSummary(
                UUID.randomUUID(),
                "recent-conversation-turns",
                "On leave",
                1,
                "",
                List.of("conversation_turns"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "conversation_turns",
                        "Lần trò chuyện gần đây",
                        List.of(
                                new FieldDetail("employee_name", "string", true, "Tên nhân viên"),
                                new FieldDetail("leave_type", "enum", true, "Loại nghỉ"),
                                new FieldDetail("start_date", "date", true, "Ngày bắt đầu")
                        )
                )),
                List.of(),
                "Nghỉ phép"
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(leave));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());

        List<ChatMessage> prior = List.of(
                message("user", "Tôi muốn xin nghỉ."),
                message("assistant", "Tôi có thể giúp bạn tạo một mục mới trong Nghỉ phép. Vui lòng cho tôi biết thông tin chi tiết để tôi chuẩn bị nhé!")
        );

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Tôi cần cung cấp những thông tin nào nhỉ?", "vi-VN", sessionId, prior);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertEquals("recent-conversation-turns", result.appKind());
        assertTrue(result.reply().contains("Nghỉ phép"));
        assertTrue(result.reply().contains("Tên nhân viên"));
        assertTrue(result.reply().contains("Ngày bắt đầu"));
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void explicitCreateAppBeatsCatalogAliasCollision() {
        String prompt = "Tạo giúp tôi một ứng dụng quản lý mua sắm. Nhân viên điền phiếu xin mua, "
                + "có thêm trường email của quản lý mà người tạo chọn; khi tạo phiếu thì gửi email "
                + "ngay cho địa chỉ quản lý đó và tự gửi phiếu vào hộp thư duyệt của quản trị viên "
                + "workspace. Quản trị viên duyệt xong mới được mua. Phiếu có mã phiếu, người yêu cầu, "
                + "phòng ban, món hàng, số tiền, ngày cần, lý do.";

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                prompt, "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.CREATE_APP, result.intent());
        assertNotNull(result.actionCard());
        assertEquals("bizapp.draft", result.actionCard().get("kind"));
        assertEquals("PROPOSED", result.actionCard().get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.actionCard().get("payload");
        assertEquals(prompt, payload.get("hint"));
        verifyNoInteractions(aiInferenceApi);
        verifyNoInteractions(bizAppRuntimeApi);
    }

    @Test
    void howToCreateAppQuestionStaysOnRag() {
        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Làm sao để tạo một ứng dụng?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        verifyNoInteractions(aiInferenceApi);
        verifyNoInteractions(bizAppRuntimeApi);
    }

    @Test
    void createAppGuardDoesNotMisreadRecordPhrases() {
        assertTrue(BizAppIntentRouter.isExplicitCreateAppRequest(
                BizAppIntentRouter.removeDiacritics(
                        "Tạo giúp tôi một ứng dụng quản lý mua sắm.")));
        assertTrue(BizAppIntentRouter.isExplicitCreateAppRequest(
                BizAppIntentRouter.removeDiacritics("Xây dựng cho tôi một app mới.")));
        assertTrue(BizAppIntentRouter.isExplicitCreateAppRequest(
                BizAppIntentRouter.removeDiacritics("Thiết kế ứng dụng theo dõi thiết bị.")));
        assertFalse(BizAppIntentRouter.isExplicitCreateAppRequest(
                BizAppIntentRouter.removeDiacritics("Tạo phiếu trong ứng dụng mua sắm.")));
        assertFalse(BizAppIntentRouter.isExplicitCreateAppRequest(
                BizAppIntentRouter.removeDiacritics("Tạo một bản ghi trong app.")));
        assertFalse(BizAppIntentRouter.isExplicitCreateAppRequest(
                BizAppIntentRouter.removeDiacritics("Cài đặt ứng dụng Booking.")));
    }

    private static ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    void chitChatReturnsBeforeAnyAppLlm() {
        router.setClassifyAllTurns(true);

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Xin chào", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void operationalRequestWithoutAppsProposesCreatingAnApp() {
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
        router.setClassifyAllTurns(true);
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse(
                        "{\"intent\":\"NONE\",\"confidence\":0.99,\"reply\":\"\",\"reason\":\"no app\"}",
                        "vylinh", 80, 20)
        );

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Tôi muốn gửi một yêu cầu mới.", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.CREATE_APP, result.intent());
        assertNotNull(result.actionCard());
        assertEquals("bizapp.draft", result.actionCard().get("kind"));
        assertEquals("Ứng dụng mới", result.actionCard().get("appName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.actionCard().get("payload");
        assertEquals("Tôi muốn gửi một yêu cầu mới.", payload.get("hint"));
    }

    @Test
    void intentModelIsConfigurable() {
        org.springframework.test.util.ReflectionTestUtils.setField(router, "intentModel",
                "qwen2.5:7b-instruct");
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse(
                        "{\"intent\":\"CREATE_APP\",\"confidence\":0.95,\"reply\":\"Tạo app\"}",
                        "qwen2.5:7b-instruct", 80, 20));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể có cách nào quản lý thiết bị IT cho tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.CREATE_APP, result.intent());
        ArgumentCaptor<StructuredJsonRequest> captor =
                ArgumentCaptor.forClass(StructuredJsonRequest.class);
        verify(aiInferenceApi, atLeastOnce()).completeJson(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(request -> "qwen2.5:7b-instruct".equals(request.modelId())));
    }

    @Test
    void painNarrativeWithNoMatchingAppProposesCreatingOne() {
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenAnswer(invocation -> {
            StructuredJsonRequest request = invocation.getArgument(0);
            if (request.systemPrompt().contains("OpenVie's app router")) {
                return new StructuredJsonResponse(
                        "{\"appKind\":null,\"confidence\":0}", "vylinh", 80, 20);
            }
            return new StructuredJsonResponse(
                    "{\"intent\":\"APP_RECORD_SUBMIT\",\"confidence\":0.95,"
                            + "\"reply\":\"Bạn muốn gửi yêu cầu mua sắm để duyệt.\"}",
                    "vylinh", 80, 20);
        });

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Công ty tôi mua sắm lộn xộn quá. Nhân viên toàn nhắn qua chat rồi quên. "
                        + "Tôi muốn mọi người điền vào một chỗ, sếp duyệt xong mới được mua, "
                        + "và tôi xem được ai xin bao nhiêu.",
                "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.CREATE_APP, result.intent());
        assertNotNull(result.actionCard());
        assertEquals("bizapp.draft", result.actionCard().get("kind"));
        assertEquals("Ứng dụng mới", result.actionCard().get("appName"));
    }

    @Test
    void policyQuestionsStayOnRagEvenWhenAppsAreInstalled() {
        InstalledAppSummary ticketing = new InstalledAppSummary(
                UUID.randomUUID(),
                "ticketing",
                "Ticketing",
                1,
                "Gửi và theo dõi phiếu yêu cầu nội bộ",
                List.of("tickets"),
                List.of(),
                "ACTIVE",
                List.of(new EntityDetail(
                        "tickets",
                        "Phiếu yêu cầu",
                        List.of(new FieldDetail("requester", "string", true, "Người gửi"))
                )),
                List.of()
        );
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(ticketing));
        router.setClassifyAllTurns(true);

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "Chính sách xử lý yêu cầu của công ty thế nào?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void disabledKillSwitchReturnsNoneImmediately() {
        router.setActionsEnabled(false);
        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER", "hãy cài app booking", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void lowConfidenceDowngradesToNone() {
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"INSTALL\",\"appKind\":\"booking\",\"confidence\":0.60,\"reply\":\"\",\"reason\":\"not sure\"}", "model", 10, 10)
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER", "cài app đặt lịch", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
    }
    @Test
    void managementNeedForUninstalledDomainProducesCreateAppDraftCard() {
        when(aiInferenceApi.completeJson(any())).thenReturn(new AiInferenceApi.StructuredJsonResponse(
                """
                {
                  "intent": "CREATE_APP",
                  "appKind": "it-equipment",
                  "confidence": 0.95,
                  "reply": "Hiện tại chưa có ứng dụng quản lý thiết bị IT. Tôi có thể tạo ứng dụng giúp bạn theo dõi mã máy, tên thiết bị, loại máy, người giữ và tình trạng.",
                  "reason": "User asks how to manage IT equipment"
                }
                """,
                "vylinh", 120, 40
        ));

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER",
                "Bạn có thể có cách nào quản lý thiết bị IT cho tôi không?", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.CREATE_APP, result.intent());
        assertNotNull(result.actionCard());
        assertEquals("bizapp.draft", result.actionCard().get("kind"));
        assertEquals("PROPOSED", result.actionCard().get("status"));
        assertTrue(result.reply().contains("thiết bị IT"));
    }


    @Test
    void unknownAppKindDowngradesToNone() {
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"INSTALL\",\"appKind\":\"hallucinated_app\",\"confidence\":0.95,\"reply\":\"\",\"reason\":\"\"}", "model", 10, 10)
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER", "cài app hallucinated_app", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
    }

    @Test
    void sensitiveQueryWithAdminVerbRoutesToAppIntent() {
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"UNINSTALL\",\"appKind\":\"crm\",\"confidence\":0.95,\"reply\":\"Xác nhận gỡ CRM\",\"reason\":\"\"}", "model", 10, 10)
        );
        when(bizAppRuntimeApi.offerUninstall(tenantId, "crm", actorId)).thenReturn(
                new AppActionOffer(UUID.randomUUID(), "bizapp.uninstall", "crm", "CRM", 1, "", "ORG_OWNER", null)
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER", "gỡ app crm có dữ liệu nhạy cảm", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.UNINSTALL, result.intent());
        assertEquals("crm", result.appKind());
        assertNotNull(result.actionCard());
    }

    @Test
    void sensitiveQueryWithoutAdminVerbRoutesToNoneRAG() {
        // "Chính sách lương mật của công ty như thế nào?" does not match gate
        var result = router.route(tenantId, orgId, actorId, "MEMBER", "Chính sách lương mật của công ty như thế nào?", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.NONE, result.intent());
        verifyNoInteractions(aiInferenceApi);
    }

    @Test
    void installIntentReturnsProposedActionCard() {
        UUID actionId = UUID.randomUUID();
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"INSTALL\",\"appKind\":\"booking\",\"confidence\":0.92,\"reply\":\"Tôi có thể cài đặt Booking cho bạn\",\"reason\":\"\"}", "model", 10, 10)
        );
        when(bizAppRuntimeApi.offerInstall(tenantId, orgId, "booking", actorId)).thenReturn(
                new AppActionOffer(actionId, "bizapp.install", "booking", "Booking", 1, "App đặt lịch", "ORG_OWNER", "platform:booking")
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER", "cài app booking", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.INSTALL, result.intent());
        assertEquals("booking", result.appKind());
        assertNotNull(result.actionCard());
        assertEquals("PROPOSED", result.actionCard().get("status"));
        assertEquals("bizapp.install", result.actionCard().get("kind"));
        assertEquals(actionId.toString(), result.actionCard().get("actionId"));
    }

    @Test
    void appDetailIntentMatchesFieldQuestionDeterministically() {
        var result = router.route(tenantId, orgId, actorId, "MEMBER", "Ứng dụng CRM bao gồm những trường nào?", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.APP_DETAIL, result.intent());
        assertEquals("crm", result.appKind());
    }
    @Test
    void listAppsWithAllOrEmptyAppKindDoesNotDowngrade() {
        when(aiInferenceApi.completeJson(any(StructuredJsonRequest.class))).thenReturn(
                new StructuredJsonResponse("{\"intent\":\"LIST_APPS\",\"appKind\":\"ALL\",\"confidence\":0.99,\"reply\":\"Danh sách ứng dụng\",\"reason\":\"\"}", "model", 10, 10)
        );

        var result = router.route(tenantId, orgId, actorId, "ORG_OWNER", "Hiện tại đang có bao nhiêu ứng dụng được cài đặt?", "vi-VN", sessionId);
        assertEquals(BizAppIntentRouter.Intent.LIST_APPS, result.intent());
        assertNull(result.appKind());
    }
}
