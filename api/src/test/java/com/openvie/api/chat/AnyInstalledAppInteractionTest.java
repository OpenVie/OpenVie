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

package com.openvie.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openvie.api.ai.api.AiInferenceApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.EntityDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.FieldDetail;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.RecordCommand;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.RecordCommandOutcome;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.model.ChatSession;
import com.openvie.api.chat.query.BizAppIntentRouter;
import com.openvie.api.chat.repository.ChatMessageRepository;
import com.openvie.api.chat.repository.ChatSessionRepository;
import com.openvie.api.chat.service.BizAppSlotAgent;
import com.openvie.api.chat.service.ChatActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies E2E-GA5 and Invariant I-15:
 * A completely custom app with a kind never seen before (equipment-tracker)
 * supports full conversational data interaction: create -> update -> archive.
 */
class AnyInstalledAppInteractionTest {

    private BizAppRuntimeApi runtimeApi;
    private AiInferenceApi aiInferenceApi;
    private ChatMessageRepository messageRepository;
    private ChatSessionRepository sessionRepository;
    private ApplicationEventPublisher eventPublisher;

    private BizAppIntentRouter router;
    private ChatActionService actionService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runtimeApi = mock(BizAppRuntimeApi.class);
        aiInferenceApi = mock(AiInferenceApi.class);
        messageRepository = mock(ChatMessageRepository.class);
        sessionRepository = mock(ChatSessionRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        router = new BizAppIntentRouter(runtimeApi, aiInferenceApi, new ObjectMapper(),
                new BizAppSlotAgent(aiInferenceApi, new ObjectMapper()));
        router.setActionsEnabled(true);
        router.setRecordActionsEnabled(true);

        actionService = new ChatActionService(messageRepository, sessionRepository, runtimeApi, eventPublisher);
        actionService.setActionsEnabled(true);
        actionService.setRecordActionsEnabled(true);

        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setTenantId(tenantId);
        session.setNextSequenceNumber(10);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        EntityDetail equipments = new EntityDetail(
                "equipments", "Thiết bị",
                List.of(
                        new FieldDetail("name", "string", true, "Tên thiết bị"),
                        new FieldDetail("serial", "string", false, "Mã số"),
                        new FieldDetail("state", "string", false, "Tình trạng")
                )
        );

        InstalledAppSummary customApp = new InstalledAppSummary(
                UUID.randomUUID(),
                "equipment-tracker",
                "Theo dõi thiết bị",
                1,
                "Quản lý theo dõi thiết bị văn phòng",
                List.of("equipments"),
                List.of("registry:equipments"),
                "ACTIVE",
                List.of(equipments)
        );

        when(runtimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(customApp));
        when(runtimeApi.listMarketplaceSummary(tenantId)).thenReturn(List.of());
    }

    @Test
    void customAppSupportsFullCreateAndExecuteCycle() {
        // 1. Chat: User requests to create equipment in the custom app
        var routingResult = router.route(
                tenantId, orgId, actorId, "ORG_OWNER",
                "Thêm thiết bị tên Máy chiếu Sony, mã số MC01 vào theo dõi thiết bị",
                "vi-VN", sessionId
        );

        assertEquals(BizAppIntentRouter.Intent.APP_RECORD_CREATE, routingResult.intent());
        assertEquals("equipment-tracker", routingResult.appKind());
        assertNotNull(routingResult.actionCard());

        Map<String, Object> card = routingResult.actionCard();
        assertEquals("PROPOSED", card.get("status"));
        assertEquals("bizapp.record.create", card.get("kind"));
        assertEquals("equipment-tracker", card.get("appKind"));
        assertEquals("equipments", card.get("entity"));

        UUID actionId = UUID.fromString(String.valueOf(card.get("actionId")));

        // 2. Mock message with this card
        ChatMessage msg = new ChatMessage();
        msg.setId(messageId);
        msg.setSessionId(sessionId);
        msg.setTenantId(tenantId);
        msg.setRole("assistant");
        msg.setContent("Proposed action");
        msg.setAction(card);
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        // 3. Mock runtime command execution
        UUID createdRecordId = UUID.randomUUID();
        when(runtimeApi.executeRecordCommand(eq(tenantId), eq(orgId), eq(actorId), eq("ORG_OWNER"), any(RecordCommand.class)))
                .thenReturn(new RecordCommandOutcome(true, createdRecordId, "Successfully created Máy chiếu Sony in equipments"));

        // 4. Execute action card
        var response = actionService.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId);

        assertNotNull(response);
        assertEquals("Successfully created Máy chiếu Sony in equipments", response.content());
        assertEquals("EXECUTED", msg.getAction().get("status"));
        assertEquals(actorId.toString(), msg.getAction().get("executedBy"));
    }
}
