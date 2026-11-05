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
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
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

        router = new BizAppIntentRouter(bizAppRuntimeApi, aiInferenceApi, objectMapper);
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
        assertTrue(BizAppIntentRouter.matchesDeterministicGate("xem cac lead trong crm"));

        // General documents / conversation without admin verbs or app vocab
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("xin chào, bạn là ai?"));
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("hợp đồng lao động mẫu năm 2026"));
        assertFalse(BizAppIntentRouter.matchesDeterministicGate("chính sách nghỉ phép của công ty"));
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
