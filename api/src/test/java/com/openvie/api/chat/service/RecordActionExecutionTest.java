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

import com.openvie.api.bizapp.api.BizAppRuntimeApi;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.RecordCommand;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.RecordCommandOutcome;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.model.ChatSession;
import com.openvie.api.chat.query.BizAppIntentRouter;
import com.openvie.api.chat.repository.ChatMessageRepository;
import com.openvie.api.chat.repository.ChatSessionRepository;
import com.openvie.api.common.exception.custom.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies Task W7.4 & W7.5: ChatActionService execution of record commands.
 */
class RecordActionExecutionTest {

    private ChatMessageRepository messageRepository;
    private ChatSessionRepository sessionRepository;
    private BizAppRuntimeApi bizAppRuntimeApi;
    private ApplicationEventPublisher eventPublisher;
    private ChatActionService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final UUID actionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageRepository = mock(ChatMessageRepository.class);
        sessionRepository = mock(ChatSessionRepository.class);
        bizAppRuntimeApi = mock(BizAppRuntimeApi.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new ChatActionService(messageRepository, sessionRepository, bizAppRuntimeApi, eventPublisher);
        service.setActionsEnabled(true);
        service.setRecordActionsEnabled(true);

        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setTenantId(tenantId);
        session.setNextSequenceNumber(5);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    }

    private ChatMessage createRecordActionMessage(String kind, String appKind, String entity, Map<String, Object> fields) {
        ChatMessage msg = new ChatMessage();
        msg.setId(messageId);
        msg.setSessionId(sessionId);
        msg.setTenantId(tenantId);
        msg.setRole("assistant");
        msg.setContent("Proposed record action");

        String hash = BizAppIntentRouter.computePayloadHash(fields);
        Map<String, Object> card = BizAppIntentRouter.buildRecordActionCard(
                kind, appKind, appKind, entity, entity, null, fields, Map.of(), "PROPOSED", List.of(), sessionId, hash
        );
        card.put("actionId", actionId.toString());
        msg.setAction(card);
        return msg;
    }

    @Test
    void executeRecordCreateSavesRecordAndMarksExecuted() {
        Map<String, Object> fields = Map.of("name", "Nguyễn Văn An", "phone", "0912345678");
        ChatMessage msg = createRecordActionMessage("bizapp.record.create", "crm", "leads", fields);
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        UUID createdRecordId = UUID.randomUUID();
        when(bizAppRuntimeApi.executeRecordCommand(eq(tenantId), eq(orgId), eq(actorId), eq("ORG_OWNER"), any(RecordCommand.class)))
                .thenReturn(new RecordCommandOutcome(true, createdRecordId, "Successfully created Nguyễn Văn An in leads"));

        var response = service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId);

        assertNotNull(response);
        assertEquals("Successfully created Nguyễn Văn An in leads", response.content());
        assertEquals("EXECUTED", msg.getAction().get("status"));
        assertEquals(actorId.toString(), msg.getAction().get("executedBy"));

        ArgumentCaptor<RecordCommand> captor = ArgumentCaptor.forClass(RecordCommand.class);
        verify(bizAppRuntimeApi).executeRecordCommand(eq(tenantId), eq(orgId), eq(actorId), eq("ORG_OWNER"), captor.capture());
        RecordCommand cmd = captor.getValue();
        assertEquals("bizapp.record.create", cmd.kind());
        assertEquals("crm", cmd.appKind());
        assertEquals("leads", cmd.entity());
        assertEquals("Nguyễn Văn An", cmd.payload().get("name"));
    }

    @Test
    void tamperedPayloadHashIsRejectedWithConflictException() {
        Map<String, Object> fields = Map.of("name", "Nguyễn Văn An");
        ChatMessage msg = createRecordActionMessage("bizapp.record.create", "crm", "leads", fields);
        // Tamper with hash
        msg.getAction().put("payloadHash", "sha256:tampered-fake-hash");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        assertThrows(ConflictException.class, () ->
                service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId));

        verifyNoInteractions(bizAppRuntimeApi);
    }
}
