package com.cacanode.api.chat.service;

import com.cacanode.api.ai.api.AiInferenceApi;
import com.cacanode.api.ai.api.AiInferenceException;
import com.cacanode.api.chat.enums.ChatTurnStatus;
import com.cacanode.api.chat.exception.ChatApiException;
import com.cacanode.api.chat.model.ChatMessage;
import com.cacanode.api.chat.model.ChatSession;
import com.cacanode.api.chat.model.ChatTurn;
import com.cacanode.api.chat.query.ChatControlPlaneService;
import com.cacanode.api.chat.repository.ChatMessageRepository;
import com.cacanode.api.chat.repository.ChatSessionRepository;
import com.cacanode.api.chat.repository.ChatTurnRepository;
import com.cacanode.api.document.api.DocumentApi;
import com.cacanode.api.tenant.api.TenantWorkspaceApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatStreamingServiceTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatTurnRepository turns = mock(ChatTurnRepository.class);
    private final TenantWorkspaceApi workspace = mock(TenantWorkspaceApi.class);
    private final DocumentApi documents = mock(DocumentApi.class);
    private final AiInferenceApi inference = mock(AiInferenceApi.class);
    private final ChatSession session = new ChatSession();
    private final Map<UUID, ChatMessage> storedMessages = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private ChatTurn turn;
    private long revision = 1;
    private ChatControlPlaneService service;
    private final ChatControlPlaneService.AnswerStreamListener listener =
            new ChatControlPlaneService.AnswerStreamListener() {
                @Override
                public void onContent(String content) {
                    assertEquals(0, assistantCount(), "Previews must not be persisted");
                    events.add("content:" + content);
                }

                @Override
                public void onReset() {
                    assertEquals(0, assistantCount(), "Invalidated answers must not be persisted");
                    events.add("reset");
                }
            };

    @BeforeEach
    void setUp() {
        session.setId(UUID.randomUUID());
        session.setTenantId(UUID.randomUUID());
        session.setUserId(UUID.randomUUID());
        session.setChatbotId(UUID.randomUUID());
        session.setKnowledgeBaseId(UUID.randomUUID());
        when(sessions.findForUpdate(session.getId(), session.getTenantId()))
                .thenReturn(Optional.of(session));
        when(messages.findBySessionIdAndSequenceNumberLessThanOrderBySequenceNumberAsc(
                eq(session.getId()), anyInt(), any())).thenReturn(List.of());
        when(messages.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(UUID.randomUUID());
            storedMessages.put(message.getId(), message);
            return message;
        });
        when(messages.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(storedMessages.get(invocation.getArgument(0))));
        when(turns.save(any(ChatTurn.class))).thenAnswer(invocation -> {
            turn = invocation.getArgument(0);
            turn.setId(UUID.randomUUID());
            return turn;
        });
        when(turns.findById(any())).thenAnswer(invocation -> Optional.ofNullable(turn));
        when(turns.findBySessionIdAndIdempotencyKeyHash(eq(session.getId()), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(turn));
        when(workspace.requireActiveKnowledgeBase(session.getTenantId(), session.getKnowledgeBaseId()))
                .thenAnswer(invocation -> new TenantWorkspaceApi.WorkspaceContext(
                        session.getTenantId(), session.getChatbotId(), session.getKnowledgeBaseId(),
                        "Tenant", revision));
        var transactionManager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        service = new ChatControlPlaneService(sessions, messages, turns, workspace, documents,
                inference, new ObjectMapper(), new TransactionTemplate(transactionManager));
    }

    @Test
    void revisionResetPrecedesReplacementPreviewAndOnlyFinalAnswerIsStored() {
        when(inference.generate(any(), any())).thenAnswer(invocation -> {
            AiInferenceApi.GenerationRequest request = invocation.getArgument(0);
            Consumer<String> preview = invocation.getArgument(1);
            preview.accept("revision-" + request.authoritativeRevision());
            if (request.authoritativeRevision() == 1) revision = 2;
            return answer(request, "committed-" + request.authoritativeRevision(), List.of());
        });

        var result = submit();

        assertEquals(List.of("content:revision-1", "reset", "content:revision-2"), events);
        assertEquals("committed-2", result.content());
        assertEquals(1, assistantCount());
        assertEquals("committed-2", storedMessages.get(turn.getAssistantMessageId()).getContent());
        assertEquals(ChatTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(2, turn.getAttemptCount());
    }

    @Test
    void secondRevisionChangeFailsWithoutPersistingEitherAnswer() {
        when(inference.generate(any(), any())).thenAnswer(invocation -> {
            AiInferenceApi.GenerationRequest request = invocation.getArgument(0);
            Consumer<String> preview = invocation.getArgument(1);
            preview.accept("draft");
            revision++;
            return answer(request, "stale", List.of());
        });

        var failure = assertThrows(ChatApiException.class, this::submit);

        assertEquals("KNOWLEDGE_BASE_CHANGED", failure.getCode());
        assertEquals(List.of("content:draft", "reset", "content:draft"), events);
        assertEquals(ChatTurnStatus.FAILED, turn.getStatus());
        assertEquals(0, assistantCount());
        verify(inference, times(2)).generate(any(), any());
    }

    @Test
    void cancellationAfterPreviewFailsTurnAndDoesNotPersistPartialAnswer() {
        try (var context = Context.current().withCancellation()) {
            when(inference.generate(any(), any())).thenAnswer(invocation -> {
                AiInferenceApi.GenerationRequest request = invocation.getArgument(0);
                Consumer<String> preview = invocation.getArgument(1);
                preview.accept("draft");
                context.cancel(new CancellationException("Disconnected"));
                return answer(request, "must not persist", List.of());
            });

            assertThrows(CancellationException.class, () -> context.run(this::submit));
        }

        assertEquals(ChatTurnStatus.FAILED, turn.getStatus());
        assertEquals("REQUEST_CANCELLED", turn.getFailureCode());
        assertEquals(0, assistantCount());
    }

    @Test
    void completedIdempotencyReplayReturnsCommittedAnswerWithoutPreviewOrInference() {
        when(inference.generate(any(), any())).thenAnswer(invocation ->
                answer(invocation.getArgument(0), "committed", List.of()));
        var first = submit();
        clearInvocations(inference);
        events.clear();

        assertEquals(first, submit());
        assertEquals(List.of(), events);
        assertEquals(1, assistantCount());
        verifyNoInteractions(inference);
    }

    @Test
    void providerFailureKeepsItsBusinessCodeAndDoesNotPersistPreview() {
        when(inference.generate(any(), any())).thenAnswer(invocation -> {
            Consumer<String> preview = invocation.getArgument(1);
            preview.accept("draft");
            throw new AiInferenceException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", "Timeout");
        });

        var failure = assertThrows(AiInferenceException.class, this::submit);

        assertEquals("MODEL_TIMEOUT", failure.getCode());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, failure.getStatus());
        assertEquals("MODEL_TIMEOUT", turn.getFailureCode());
        assertEquals(0, assistantCount());
    }

    @Test
    void crossTenantCitationDenialStillPreventsCommit() {
        UUID documentId = UUID.randomUUID();
        var citation = new AiInferenceApi.Citation("citation", documentId.toString(), "source",
                null, 0, 1.0, "snippet", null, null, List.of(), null, null, null, null);
        when(inference.generate(any(), any())).thenAnswer(invocation -> {
            Consumer<String> preview = invocation.getArgument(1);
            preview.accept("draft without source cards");
            return answer(invocation.getArgument(0), "invalid", List.of(citation));
        });
        doThrow(new IllegalArgumentException("Wrong tenant")).when(documents)
                .validateCitations(session.getTenantId(), session.getKnowledgeBaseId(), List.of(documentId));

        var failure = assertThrows(ChatApiException.class, this::submit);

        assertEquals("INVALID_CITATION", failure.getCode());
        assertEquals(ChatTurnStatus.FAILED, turn.getStatus());
        assertEquals(0, assistantCount());
    }

    private com.cacanode.api.chat.dto.ChatDtos.AssistantMessageResponse submit() {
        return service.submitEmployeeMessage(session.getTenantId(), session.getUserId(), session.getId(),
                "Question", Map.of(), "idempotency-key", "request-id", listener);
    }

    private long assistantCount() {
        return storedMessages.values().stream().filter(message -> "assistant".equals(message.getRole())).count();
    }

    private AiInferenceApi.GeneratedAnswer answer(AiInferenceApi.GenerationRequest request,
                                                String content, List<AiInferenceApi.Citation> citations) {
        return new AiInferenceApi.GeneratedAnswer(request.generationId(), request.authoritativeRevision(),
                content, citations, 1L, 1L, "none", null, null);
    }
}
