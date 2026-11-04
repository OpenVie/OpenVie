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
import com.openvie.api.ai.api.AiInferenceApi.GenerationRequest;
import com.openvie.api.chat.dto.ChatDtos;
import com.openvie.api.chat.enums.ChatChannel;
import com.openvie.api.chat.enums.ChatSessionStatus;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.model.ChatSession;
import com.openvie.api.chat.model.ChatTurn;
import com.openvie.api.chat.repository.ChatMessageRepository;
import com.openvie.api.chat.repository.ChatSessionRepository;
import com.openvie.api.chat.repository.ChatTurnRepository;
import com.openvie.api.chat.service.implement.ChatRuntimeApiImpl;
import com.openvie.api.document.api.DocumentApi;
import com.openvie.api.tenant.api.TenantWorkspaceApi;
import com.openvie.api.tenant.api.TenantWorkspaceApi.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AllChatEntryPointsExclusionTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ChatTurnRepository turnRepository;
    private TenantWorkspaceApi tenantWorkspaceApi;
    private DocumentApi documentApi;
    private AiInferenceApi inferenceClient;
    private ChatControlPlaneService controlPlaneService;
    private ChatRuntimeApiImpl chatRuntimeApi;

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

        chatRuntimeApi = new ChatRuntimeApiImpl(controlPlaneService);

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

        when(inferenceClient.generate(any(GenerationRequest.class), any())).thenReturn(
                new AiInferenceApi.GeneratedAnswer(
                        UUID.randomUUID(), 1L, "Sample answer", List.of(), 10L, 10L, "none", 0L, 0L, List.of()
                )
        );
    }

    @Test
    void submitEmployeeMessageAppliesDefaultExclusionsForNoneIntent() {
        controlPlaneService.submitEmployeeMessage(
                tenantId, userId, sessionId, "Câu hỏi về hợp đồng", Map.of(), null, null, "req-1",
                new ChatControlPlaneService.AnswerStreamListener() {
                    @Override public void onContent(String c) {}
                    @Override public void onReset() {}
                }
        );

        ArgumentCaptor<GenerationRequest> captor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(inferenceClient).generate(captor.capture(), any());

        GenerationRequest request = captor.getValue();
        assertNotNull(request.excludedDocumentKinds());
        assertEquals(List.of("BIZAPP_RECORD", "BIZAPP_DIGEST"), request.excludedDocumentKinds());
    }

    @Test
    void submitScopedTurnAppliesDefaultExclusionsForNoneIntent() {
        chatRuntimeApi.submitScopedTurn(tenantId, userId, sessionId, "Câu hỏi từ runtime API", null);

        ArgumentCaptor<GenerationRequest> captor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(inferenceClient).generate(captor.capture(), any());

        GenerationRequest request = captor.getValue();
        assertNotNull(request.excludedDocumentKinds());
        assertEquals(List.of("BIZAPP_RECORD", "BIZAPP_DIGEST"), request.excludedDocumentKinds());
    }
}
