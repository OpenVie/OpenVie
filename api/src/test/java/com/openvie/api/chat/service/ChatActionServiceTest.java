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
import com.openvie.api.bizapp.api.BizAppRuntimeApi.AppActionOffer;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstalledAppSummary;
import com.openvie.api.bizapp.api.BizAppRuntimeApi.InstallOutcome;
import com.openvie.api.chat.model.ChatMessage;
import com.openvie.api.chat.model.ChatSession;
import com.openvie.api.chat.repository.ChatMessageRepository;
import com.openvie.api.chat.repository.ChatSessionRepository;
import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.exception.custom.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatActionServiceTest {

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

        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setTenantId(tenantId);
        session.setNextSequenceNumber(5);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    }

    private ChatMessage createProposedMessage(String kind, String appKind, String status) {
        ChatMessage msg = new ChatMessage();
        msg.setId(messageId);
        msg.setSessionId(sessionId);
        msg.setTenantId(tenantId);
        msg.setRole("assistant");
        msg.setContent("Would you like to install " + appKind + "?");

        Map<String, Object> action = new HashMap<>();
        action.put("type", "bizapp_action");
        action.put("actionId", actionId.toString());
        action.put("kind", kind);
        action.put("status", status);
        action.put("appKind", appKind);
        action.put("appName", appKind.toUpperCase());
        action.put("payload", Map.of("templateRef", "platform:" + appKind));

        msg.setAction(action);
        return msg;
    }

    @Test
    void nonOrgOwnerRoleIsDeniedByServer() {
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "PROPOSED");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        assertThrows(AccessDeniedException.class, () ->
                service.executeAction(tenantId, orgId, actorId, "MEMBER", messageId, actionId));

        assertEquals("DENIED", msg.getAction().get("status"));
        verify(messageRepository).save(msg);
        verify(eventPublisher).publishEvent(any(AuditLogEvent.class));
        verifyNoInteractions(bizAppRuntimeApi);
    }

    @Test
    void nonExecutableStatusIsRejectedWithConflict() {
        // Statuses outside PROPOSED/FAILED (e.g. a DENIED card) are rejected.
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "DENIED");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        assertThrows(ConflictException.class, () ->
                service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId));
        verifyNoInteractions(bizAppRuntimeApi);
    }

    @Test
    void failedStatusIsRetryableUntilExecuted() {
        // A card marked FAILED by a previous failed execution must not be a
        // dead end: retry re-enters the normal execution path.
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "FAILED");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.executeOffer(eq(tenantId), eq(orgId), eq(actorId), any(AppActionOffer.class)))
                .thenReturn(new InstallOutcome(true, UUID.randomUUID(), "Booking installed"));

        var response = service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId);

        assertEquals("Booking installed", response.content());
        assertEquals("EXECUTED", msg.getAction().get("status"));
    }

    @Test
    void actionIdMismatchIsRejected() {
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "PROPOSED");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        assertThrows(ConflictException.class, () ->
                service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, UUID.randomUUID()));
    }

    @Test
    void parametersReadFromServerDbNotClient() {
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "PROPOSED");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());

        UUID installId = UUID.randomUUID();
        when(bizAppRuntimeApi.executeOffer(eq(tenantId), eq(orgId), eq(actorId), any(AppActionOffer.class)))
                .thenReturn(new InstallOutcome(true, installId, "Booking installed"));

        var response = service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId);

        ArgumentCaptor<AppActionOffer> offerCaptor = ArgumentCaptor.forClass(AppActionOffer.class);
        verify(bizAppRuntimeApi).executeOffer(eq(tenantId), eq(orgId), eq(actorId), offerCaptor.capture());

        AppActionOffer offer = offerCaptor.getValue();
        assertEquals(actionId, offer.actionId());
        assertEquals("bizapp.install", offer.kind());
        assertEquals("booking", offer.appKind());
        assertEquals("platform:booking", offer.templateRef());

        assertEquals("EXECUTED", msg.getAction().get("status"));
        assertEquals(actorId.toString(), msg.getAction().get("executedBy"));
        assertNotNull(msg.getAction().get("executedAt"));
        assertEquals("Booking installed", response.content());
    }

    @Test
    void executeActionFallsBackToActionIdLookupWhenMessageIdNullOrInvalid() {
        ChatMessage msg = createProposedMessage("bizapp.uninstall", "booking", "PROPOSED");
        when(messageRepository.findByTenantIdAndActionId(tenantId, actionId.toString())).thenReturn(Optional.of(msg));
        InstalledAppSummary booking = new InstalledAppSummary(UUID.randomUUID(), "booking", "Booking", 1, "", List.of(), List.of(), "ACTIVE");
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of(booking));
        when(bizAppRuntimeApi.executeOffer(eq(tenantId), eq(orgId), eq(actorId), any(AppActionOffer.class)))
                .thenReturn(new InstallOutcome(true, null, "Successfully uninstalled Booking"));

        var response = service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", "non-uuid-fake-message-id", actionId);
        assertNotNull(response);
        assertEquals("Successfully uninstalled Booking", response.content());
        assertEquals("EXECUTED", msg.getAction().get("status"));
    }

    @Test
    void idempotentExecutionReturnsExistingResult() {
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "EXECUTED");
        msg.getAction().put("result", Map.of("installationId", UUID.randomUUID().toString(), "message", "Already installed"));
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));

        var response = service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId);

        assertEquals("Already installed", response.content());
        verifyNoInteractions(bizAppRuntimeApi);
    }

    @Test
    void executionRecordsAuditWithActor() {
        ChatMessage msg = createProposedMessage("bizapp.install", "booking", "PROPOSED");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));
        when(bizAppRuntimeApi.listInstalledSummary(tenantId)).thenReturn(List.of());
        when(bizAppRuntimeApi.executeOffer(any(), any(), any(), any()))
                .thenReturn(new InstallOutcome(true, UUID.randomUUID(), "Success"));

        service.executeAction(tenantId, orgId, actorId, "ORG_OWNER", messageId, actionId);

        ArgumentCaptor<AuditLogEvent> auditCaptor = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(auditCaptor.capture());

        AuditLogEvent audit = auditCaptor.getValue();
        assertEquals(tenantId, audit.getTenantId());
        assertEquals(actorId, audit.getUserId());
        assertEquals(LogAction.CHAT_APP_ACTION_EXECUTED, audit.getAction());
    }
}
