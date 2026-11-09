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
import com.openvie.api.bizapp.api.BizAppRuntimeApi.ActionCapabilities;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.MarketplaceTemplateSummary;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.service.BizAppSlotAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The router only proposes actions the caller's manifest role may execute, and
 * record actions must target a real record id from the conversation.
 */
class BizAppIntentRouterCapabilityTest {

    private static final String UPDATE_JSON = """
            {"intent":"APP_RECORD_UPDATE","appKind":"crm","entity":"leads",
             "fields":{"name":"An"},"confidence":0.92,"reply":"","reason":"update"}
            """;

    private BizAppRuntimeApi bizAppRuntimeApi;
    private AiInferenceApi aiInferenceApi;
    private BizAppIntentRouter router;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bizAppRuntimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);

        router = new BizAppIntentRouter(bizAppRuntimeApi, aiInferenceApi, new ObjectMapper(),
                new BizAppSlotAgent(aiInferenceApi, new ObjectMapper()));
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(true);

        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(crm()));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of(booking()));
    }

    @Test
    void aRoleWithoutTheCapabilityGetsADenialInsteadOfACard() {
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "crm", new ActionCapabilities(false, true, false, false, false, false)));

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "tạo lead mới trong crm", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, result.intent());
        assertNull(result.actionCard(), "no card may be proposed for a denied capability");
        assertTrue(result.reply().contains("không được phép"), result.reply());
        verify(bizAppRuntimeApi, never()).offerInstall(any(), any(), any(), any());
    }

    @Test
    void anInstalledAppTheRoleCannotReadIsNeverOfferedAsAnInstall() {
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "crm", new ActionCapabilities(false, false, false, false, false, false)));
        when(bizAppRuntimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of(crmTemplate(), booking()));

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "tạo lead mới trong crm", "vi-VN", sessionId);

        assertNull(result.actionCard());
        verify(bizAppRuntimeApi, never()).offerInstall(any(), any(), any(), any());
    }

    @Test
    void updateWithoutARecordIdAsksWhichRecordInsteadOfBuildingACard() {
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "crm", new ActionCapabilities(false, true, true, true, true, false)));
        when(aiInferenceApi.completeJson(any())).thenReturn(
                new StructuredJsonResponse(UPDATE_JSON, "vylinh", 80, 20));

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "đổi tên thành An giúp tôi", "vi-VN", sessionId);

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_UPDATE, result.intent());
        assertNull(result.actionCard(), "a record action without a target record must not be proposed");
        assertTrue(result.reply().contains("bản ghi cụ thể"), result.reply());
    }

    @Test
    void updateUsesTheRecordIdOfTheLastRecordActionCard() {
        UUID recordId = UUID.fromString("2b5c16f9-858b-4e29-8a57-cab416b94789");
        when(bizAppRuntimeApi.listActionCapabilities(tenantId, "MEMBER")).thenReturn(Map.of(
                "crm", new ActionCapabilities(false, true, true, true, true, false)));
        when(aiInferenceApi.completeJson(any())).thenReturn(
                new StructuredJsonResponse(UPDATE_JSON, "vylinh", 80, 20));

        ChatMessage execution = new ChatMessage();
        execution.setRole("assistant");
        execution.setAction(Map.of(
                "kind", "bizapp.record.create",
                "appKind", "crm",
                "entity", "leads",
                "status", "EXECUTED",
                "result", Map.of("recordId", recordId.toString())));

        var result = router.route(tenantId, orgId, actorId, "MEMBER",
                "đổi tên thành An giúp tôi", "vi-VN", sessionId, List.of(execution));

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_UPDATE, result.intent());
        assertEquals(recordId.toString(), result.actionCard().get("recordId"));
        assertEquals("MEMBER", result.actionCard().get("requiresRole"),
                "cards carry the caller role, not a hardcoded owner requirement");
    }

    @Test
    void recordIdResolutionPrefersAnExplicitUuidInTheMessage() {
        UUID recordId = UUID.fromString("2b5c16f9-858b-4e29-8a57-cab416b94789");

        assertEquals(recordId, BizAppIntentRouter.resolveRecordId(
                "cập nhật bản ghi " + recordId, List.of(), "crm", "leads"));
        assertNull(BizAppIntentRouter.resolveRecordId(
                "cập nhật bản ghi này", List.of(), "crm", "leads"));
    }

    private InstalledAppSummary crm() {
        return new InstalledAppSummary(
                UUID.randomUUID(), "crm", "CRM", 1, "", List.of("leads"), List.of(), "ACTIVE",
                List.of(new EntityDetail("leads", "Leads",
                        List.of(new FieldDetail("name", "string", true, "Name")))),
                List.of());
    }

    private MarketplaceTemplateSummary crmTemplate() {
        return new MarketplaceTemplateSummary("crm", "CRM", 1, "", List.of(), List.of(), "platform:crm");
    }

    private MarketplaceTemplateSummary booking() {
        return new MarketplaceTemplateSummary("booking", "Booking", 1, "", List.of(), List.of(), "platform:booking");
    }
}
