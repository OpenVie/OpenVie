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
import com.openvie.api.chat.dto.ChatDtos.AssistantMessageResponse;
import com.openvie.api.chat.enums.ChatChannel;
import com.openvie.api.chat.enums.ChatSessionStatus;
import com.openvie.api.chat.enums.ChatTurnStatus;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.model.ChatSession;
import com.openvie.api.chat.model.ChatTurn;
import com.openvie.api.chat.repository.ChatMessageRepository;
import com.openvie.api.chat.repository.ChatSessionRepository;
import com.openvie.api.chat.repository.ChatTurnRepository;
import com.openvie.api.chat.service.CatalogAnswerComposer;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.tenant.api.TenantWorkspaceApi;
import com.openvie.api.tenant.api.TenantWorkspaceApi.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatControlPlaneShortCircuitTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ChatTurnRepository turnRepository;
    private TenantWorkspaceApi tenantWorkspaceApi;
    private DocumentApi documentApi;
    private AiInferenceApi inferenceClient;
    private BizAppIntentRouter intentRouter;
    private CatalogAnswerComposer catalogAnswerComposer;
    private BizAppRuntimeApi bizAppRuntimeApi;
    private ChatControlPlaneService controlPlaneService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID chatbotId = UUID.randomUUID();
    private final UUID kbId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        turnRepository = mock(ChatTurnRepository.class);
        tenantWorkspaceApi = mock(TenantWorkspaceApi.class);
        documentApi = mock(DocumentApi.class);
        inferenceClient = mock(AiInferenceApi.class);
        intentRouter = mock(BizAppIntentRouter.class);
        catalogAnswerComposer = mock(CatalogAnswerComposer.class);
        bizAppRuntimeApi = mock(BizAppRuntimeApi.class);

        ObjectMapper objectMapper = new ObjectMapper();
        TransactionTemplate transactions = mock(TransactionTemplate.class);

        when(transactions.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });

        controlPlaneService = new ChatControlPlaneService(
                sessionRepository,
                messageRepository,
                turnRepository,
                tenantWorkspaceApi,
                documentApi,
                inferenceClient,
                objectMapper,
                transactions
        );

        controlPlaneService.setIntentRouter(intentRouter);
        controlPlaneService.setCatalogAnswerComposer(catalogAnswerComposer);
        controlPlaneService.setBizAppRuntimeApi(bizAppRuntimeApi);

        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setChatbotId(chatbotId);
        session.setKnowledgeBaseId(kbId);
        session.setChannel(ChatChannel.EMPLOYEE_PLAYGROUND);
        session.setStatus(ChatSessionStatus.OPEN);
        session.setLocale("vi-VN");
        session.setNextSequenceNumber(1);

        when(sessionRepository.findForUpdate(sessionId, tenantId)).thenReturn(Optional.of(session));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(tenantWorkspaceApi.requireActiveKnowledgeBase(tenantId, kbId))
                .thenReturn(new WorkspaceContext(tenantId, chatbotId, kbId, "Acme", 1L));

        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(turnRepository.save(any(ChatTurn.class))).thenAnswer(i -> {
            ChatTurn t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(turnRepository.findById(any())).thenAnswer(i -> {
            ChatTurn t = new ChatTurn();
            t.setId(i.getArgument(0));
            t.setSessionId(sessionId);
            t.setTenantId(tenantId);
            return Optional.of(t);
        });
    }

    @Test
    void nonNoneIntentShortCircuitsInferenceAndRecordsAction() {
        Map<String, Object> card = Map.of(
                "type", "bizapp_action",
                "kind", "bizapp.install",
                "status", "PROPOSED",
                "appKind", "crm"
        );
        BizAppIntentRouter.RoutingResult routing = new BizAppIntentRouter.RoutingResult(
                BizAppIntentRouter.Intent.INSTALL,
                "crm",
                BigDecimal.valueOf(0.95),
                "Tôi có thể hỗ trợ bạn cài đặt CRM",
                "Matched install",
                null,
                card
        );

        when(intentRouter.route(eq(tenantId), any(), eq(userId), any(), eq("cài app crm"), any(), eq(sessionId)))
                .thenReturn(routing);

        AssistantMessageResponse response = controlPlaneService.submitEmployeeMessage(
                tenantId, userId, sessionId, "cài app crm", Map.of(), null, "key-1", "req-1",
                new ChatControlPlaneService.AnswerStreamListener() {
                    @Override public void onContent(String c) {}
                    @Override public void onReset() {}
                }
        );

        // Verification: inferenceClient was NOT called!
        verifyNoInteractions(inferenceClient);

        // Verification: turn and message were saved with action
        verify(turnRepository, atLeastOnce()).save(any(ChatTurn.class));
        verify(messageRepository, atLeast(2)).save(any(ChatMessage.class));

        assertNotNull(response);
        assertEquals("Tôi có thể hỗ trợ bạn cài đặt CRM", response.content());
        assertNotNull(response.action());
        assertEquals("bizapp_action", response.action().get("type"));
        assertEquals("bizapp.install", response.action().get("kind"));
        assertEquals("PROPOSED", response.action().get("status"));
    }

    @Test
    void listAppsShortCircuitUsesCatalogAnswerComposer() {
        BizAppIntentRouter.RoutingResult routing = new BizAppIntentRouter.RoutingResult(
                BizAppIntentRouter.Intent.LIST_APPS,
                null,
                BigDecimal.valueOf(0.98),
                null,
                "List apps",
                null,
                null
        );

        when(intentRouter.route(eq(tenantId), any(), eq(userId), any(), eq("danh sách ứng dụng"), any(), eq(sessionId)))
                .thenReturn(routing);
        when(catalogAnswerComposer.composeListApps(eq(tenantId), eq("vi-VN")))
                .thenReturn("### Danh sách ứng dụng đã cài đặt: CRM, Booking");

        AssistantMessageResponse response = controlPlaneService.submitEmployeeMessage(
                tenantId, userId, sessionId, "danh sách ứng dụng", Map.of(), null, "key-2", "req-2",
                new ChatControlPlaneService.AnswerStreamListener() {
                    @Override public void onContent(String c) {}
                    @Override public void onReset() {}
                }
        );

        verifyNoInteractions(inferenceClient);
        verify(catalogAnswerComposer).composeListApps(eq(tenantId), eq("vi-VN"));
        assertEquals("### Danh sách ứng dụng đã cài đặt: CRM, Booking", response.content());
    }
}
