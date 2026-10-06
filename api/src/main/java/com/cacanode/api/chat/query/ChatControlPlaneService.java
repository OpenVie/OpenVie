package com.cacanode.api.chat.query;

import com.cacanode.api.ai.api.AiInferenceApi;
import com.cacanode.api.chat.dto.ChatDtos;
import com.cacanode.api.chat.enums.ChatChannel;
import com.cacanode.api.chat.enums.ChatSessionStatus;
import com.cacanode.api.chat.enums.ChatTurnStatus;
import com.cacanode.api.chat.exception.ChatApiException;
import com.cacanode.api.chat.model.ChatMessage;
import com.cacanode.api.chat.model.ChatSession;
import com.cacanode.api.chat.model.ChatTurn;
import com.cacanode.api.chat.repository.ChatMessageRepository;
import com.cacanode.api.chat.repository.ChatSessionRepository;
import com.cacanode.api.chat.repository.ChatTurnRepository;
import com.cacanode.api.document.api.DocumentApi;
import com.cacanode.api.tenant.api.TenantWorkspaceApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ChatControlPlaneService {
    private static final String PROMPT_SCHEMA_VERSION = "chat-prompts-v4";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatTurnRepository turnRepository;
    private final TenantWorkspaceApi tenantWorkspaceApi;
    private final DocumentApi documentApi;
    private final AiInferenceApi inferenceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    @Autowired(required = false)
    private NamedParameterJdbcTemplate namedJdbc;

    public ChatDtos.SessionResponse createEmployeeSession(
            UUID tenantId, UUID userId, ChatDtos.CreateSessionRequest request) {
        return transactions.execute(status -> createSession(
                tenantId, userId, request.chatbotId(), request.knowledgeBaseId(),
                request.locale()));
    }

    public ChatDtos.AssistantMessageResponse submitEmployeeMessage(
            UUID tenantId, UUID userId, UUID sessionId, String content,
            Map<String, Object> metadata, String idempotencyKey, String requestId) {
        return submit(tenantId, userId, sessionId, content, metadata, idempotencyKey, requestId);
    }

    private ChatDtos.AssistantMessageResponse submit(
            UUID tenantId, UUID userId, UUID sessionId, String content,
            Map<String, Object> metadata, String idempotencyKey, String requestId) {
        PreparedTurn prepared = transactions.execute(status -> prepareTurn(
                tenantId, userId, sessionId, content, metadata, idempotencyKey, requestId));
        Objects.requireNonNull(prepared);
        if (prepared.replayed() != null) {
            return prepared.replayed();
        }

        GenerationContext context = prepared.context();
        for (int revisionAttempt = 0; revisionAttempt < 2; revisionAttempt++) {
            try {
                AiInferenceApi.GeneratedAnswer answer = inferenceClient.generate(context.toRequest());
                GenerationContext completedContext = context;
                FinalizeResult finalized = transactions.execute(status -> finalizeTurn(
                        prepared.turnId(), answer, completedContext));
                Objects.requireNonNull(finalized);
                if (!finalized.revisionChanged()) {
                    return finalized.response();
                }
                if (revisionAttempt == 1) {
                    markFailed(prepared.turnId(), "REVISION_CHANGED");
                    throw new ChatApiException(HttpStatus.CONFLICT, "KNOWLEDGE_BASE_CHANGED",
                            "The knowledge base changed while the answer was generated.");
                }
                context = transactions.execute(status -> rebuildContext(prepared.turnId(), requestId));
                Objects.requireNonNull(context);
            } catch (ChatApiException exception) {
                if (!"KNOWLEDGE_BASE_CHANGED".equals(exception.getCode())) {
                    markFailed(prepared.turnId(), exception.getCode());
                }
                throw exception;
            } catch (RuntimeException exception) {
                markFailed(prepared.turnId(), "AI_FAILURE");
                throw new ChatApiException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR",
                        "The model provider could not complete the request.");
            }
        }
        throw new IllegalStateException("Unreachable generation state");
    }

    private PreparedTurn prepareTurn(
            UUID tenantId, UUID userId, UUID sessionId, String content,
            Map<String, Object> metadata, String idempotencyKey, String requestId) {
        ChatSession session = lockedAuthorizedSession(tenantId, userId, sessionId);
        String keyHash = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : sha256(idempotencyKey);
        String fingerprint = fingerprint(content, metadata, null);
        if (keyHash != null) {
            ChatTurn duplicate = turnRepository.findBySessionIdAndIdempotencyKeyHash(sessionId, keyHash)
                    .orElse(null);
            if (duplicate != null) {
                if (!duplicate.getRequestFingerprint().equals(fingerprint)) {
                    throw new ChatApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                            "The idempotency key was already used for different content.");
                }
                if (duplicate.getStatus() == ChatTurnStatus.PENDING) {
                    throw new ChatApiException(HttpStatus.CONFLICT, "MESSAGE_IN_PROGRESS",
                            "A message with this idempotency key is still being processed.");
                }
                if (duplicate.getStatus() == ChatTurnStatus.COMPLETED) {
                    ChatMessage assistant = messageRepository.findById(duplicate.getAssistantMessageId())
                            .orElseThrow();
                    return new PreparedTurn(duplicate.getId(), null,
                            toAssistant(assistant, session));
                }
                duplicate.setStatus(ChatTurnStatus.PENDING);
                duplicate.setFailureCode(null);
                duplicate.setAttemptCount(duplicate.getAttemptCount() + 1);
                ChatMessage userMessage = messageRepository.findById(duplicate.getUserMessageId())
                        .orElseThrow();
                GenerationContext context = captureContext(session, duplicate, userMessage, requestId);
                duplicate.setKnowledgeRevision(context.revision());
                duplicate.setGenerationContext(context.asMap());
                return new PreparedTurn(duplicate.getId(), context, null);
            }
        }

        List<ChatMessage> prior = firstPriorMessages(sessionId, Integer.MAX_VALUE);
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setTenantId(tenantId);
        userMessage.setUserId(userId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        userMessage.setSequenceNumber(allocateSequence(session));
        userMessage = messageRepository.save(userMessage);

        var workspace = tenantWorkspaceApi.requireActiveKnowledgeBase(tenantId, session.getKnowledgeBaseId());
        ChatTurn turn = new ChatTurn();
        turn.setSessionId(sessionId);
        turn.setTenantId(tenantId);
        turn.setGenerationId(UUID.randomUUID());
        turn.setStatus(ChatTurnStatus.PENDING);
        turn.setIdempotencyKeyHash(keyHash);
        turn.setRequestFingerprint(fingerprint);
        turn.setUserMessageId(userMessage.getId());
        turn.setKnowledgeRevision(workspace.searchRevision());
        turn.setAttemptCount(1);
        turn = turnRepository.save(turn);
        GenerationContext context = captureContext(session, turn, userMessage, prior, requestId);
        turn.setGenerationContext(context.asMap());
        return new PreparedTurn(turn.getId(), context, null);
    }

    private FinalizeResult finalizeTurn(
            UUID turnId, AiInferenceApi.GeneratedAnswer answer, GenerationContext context) {
        ChatTurn turn = turnRepository.findById(turnId).orElseThrow();
        ChatSession session = sessionRepository.findForUpdate(turn.getSessionId(), turn.getTenantId())
                .orElseThrow(() -> sessionNotFound());
        var workspace = tenantWorkspaceApi.requireActiveKnowledgeBase(
                session.getTenantId(), session.getKnowledgeBaseId());
        if (workspace.searchRevision() != context.revision()) {
            return new FinalizeResult(true, null);
        }
        List<ChatDtos.CitationResponse> persistedCitations =
                answer.citations().stream().map(this::toChatCitation).toList();
        String persistedAnswer = answer.answer();
        validateCitations(session, persistedCitations);
        ChatMessage assistant = new ChatMessage();
        assistant.setSessionId(session.getId());
        assistant.setTenantId(session.getTenantId());
        assistant.setRole("assistant");
        assistant.setContent(persistedAnswer);
        assistant.setCitations(persistedCitations.stream().map(this::citationMap).toList());
        assistant.setSequenceNumber(allocateSequence(session));
        assistant = messageRepository.save(assistant);
        turn.setAssistantMessageId(assistant.getId());
        turn.setStatus(ChatTurnStatus.COMPLETED);
        turn.setFailureCode(null);
        return new FinalizeResult(false, toAssistant(assistant, session));
    }

    private ChatDtos.CitationResponse toChatCitation(AiInferenceApi.Citation citation) {
        return new ChatDtos.CitationResponse(
                citation.id(), citation.documentId(), citation.sourceName(), citation.pageNumber(),
                citation.chunkIndex(), citation.score(), citation.snippet(), citation.unitId(),
                citation.modality(), citation.sectionPath(), citation.blockType(), citation.sheetName(),
                citation.cellRange(), citation.tableId(), null);
    }

    private GenerationContext rebuildContext(UUID turnId, String requestId) {
        ChatTurn turn = turnRepository.findById(turnId).orElseThrow();
        ChatSession session = sessionRepository.findForUpdate(turn.getSessionId(), turn.getTenantId())
                .orElseThrow(() -> sessionNotFound());
        ChatMessage userMessage = messageRepository.findById(turn.getUserMessageId()).orElseThrow();
        GenerationContext context = captureContext(session, turn, userMessage, requestId);
        turn.setKnowledgeRevision(context.revision());
        turn.setGenerationContext(context.asMap());
        turn.setAttemptCount(turn.getAttemptCount() + 1);
        return context;
    }

    private void markFailed(UUID turnId, String code) {
        transactions.executeWithoutResult(status -> turnRepository.findById(turnId).ifPresent(turn -> {
            if (turn.getStatus() != ChatTurnStatus.COMPLETED) {
                turn.setStatus(ChatTurnStatus.FAILED);
                turn.setFailureCode(code);
            }
        }));
    }

    private ChatDtos.SessionResponse createSession(
            UUID tenantId, UUID userId, UUID chatbotId, UUID knowledgeBaseId, String locale) {
        tenantWorkspaceApi.requireActiveWorkspace(tenantId, chatbotId, knowledgeBaseId);
        ChatSession session = new ChatSession();
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setChatbotId(chatbotId);
        session.setKnowledgeBaseId(knowledgeBaseId);
        session.setLocale(locale == null || locale.isBlank() ? "vi-VN" : locale);
        session.setChannel(ChatChannel.EMPLOYEE_PLAYGROUND);
        session = sessionRepository.save(session);
        return toSession(session);
    }

    public List<ChatDtos.MessageResponse> history(
            UUID tenantId, UUID userId, UUID sessionId, int limit, int after) {
        ChatSession session = authorizedSession(tenantId, userId, sessionId);
        return messageRepository.findBySessionIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                session.getId(), Math.max(0, after), PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))
                .stream().map(message -> toMessage(message, session)).toList();
    }

    public void close(UUID tenantId, UUID userId, UUID sessionId) {
        transactions.executeWithoutResult(status -> {
            ChatSession session = lockedAuthorizedSession(tenantId, userId, sessionId);
            if (session.getStatus() == ChatSessionStatus.CLOSED) {
                return;
            }
            session.setStatus(ChatSessionStatus.CLOSED);
            session.setClosedAt(LocalDateTime.now());
        });
    }

    public List<ChatDtos.PlaygroundSessionResponse> playground(
            UUID tenantId, UUID userId, int limit, int offset) {
        if (namedJdbc != null) {
            return playgroundPage(tenantId, userId, limit, offset, null, null, null,
                    null, null, null, null).sessions();
        }
        int requested = Math.min(Math.max(limit, 1), 100);
        List<ChatSession> all = sessionRepository
                .findByTenantIdAndUserIdAndChannelAndHiddenAtIsNullOrderByLastActivityAtDesc(
                        tenantId, userId, ChatChannel.EMPLOYEE_PLAYGROUND,
                        PageRequest.of(0, Math.min(offset + requested, 1000)));
        return all.stream().skip(Math.max(offset, 0)).limit(requested).map(session -> {
            String title = messageRepository.findFirstBySessionIdAndRoleOrderBySequenceNumberAsc(
                    session.getId(), "user").map(ChatMessage::getContent).map(String::trim)
                    .filter(value -> !value.isBlank()).map(value -> value.substring(0, Math.min(60, value.length())))
                    .orElse(session.getCreatedAt().toLocalDate().toString());
            return new ChatDtos.PlaygroundSessionResponse(
                    session.getId(), title, messageRepository.countBySessionId(session.getId()),
                    session.getStatus().name(), session.getCreatedAt(), session.getLastActivityAt());
        }).toList();
    }

    public PlaygroundPage playgroundPage(
            UUID tenantId, UUID userId, int limit, int offset, String cursor,
            String searchText, String status, LocalDate activityFrom, LocalDate activityTo,
            String sort, String direction
    ) {
        int requested = Math.min(Math.max(limit, 1), 100);
        int requestedOffset = Math.max(offset, 0);
        String query = normalizeSearch(searchText);
        String requestedStatus = normalizeStatus(status);
        String requestedSort = normalizePlaygroundSort(sort);
        String requestedDirection = normalizeDirection(direction);
        validateDates(activityFrom, activityTo, "Activity");
        if (namedJdbc == null) {
            return new PlaygroundPage(playground(tenantId, userId, requested, requestedOffset), null);
        }

        String sortColumn = "created".equals(requestedSort) ? "s.created_at" : "s.last_activity_at";
        String comparison = "asc".equals(requestedDirection) ? ">" : "<";
        String order = "asc".equals(requestedDirection) ? "ASC" : "DESC";
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.status, s.created_at, s.last_activity_at
                FROM chat_sessions s
                WHERE s.tenant_id = :tenantId AND s.user_id = :userId
                  AND s.channel = 'EMPLOYEE_PLAYGROUND' AND s.hidden_at IS NULL
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("userId", userId);
        appendStatusDateAndTranscript(sql, params, requestedStatus, activityFrom, activityTo,
                query, sortColumn, true);
        if (cursor != null && !cursor.isBlank()) {
            CursorValue decoded = decodeCursor(cursor);
            sql.append(" AND (").append(sortColumn).append(", s.id) ").append(comparison)
                    .append(" (:cursorTime, :cursorId)");
            params.addValue("cursorTime", decoded.timestamp()).addValue("cursorId", decoded.id());
        }
        sql.append(" ORDER BY ").append(sortColumn).append(' ').append(order)
                .append(", s.id ").append(order).append(" LIMIT :fetch");
        if (cursor == null || cursor.isBlank()) sql.append(" OFFSET :offset");
        params.addValue("fetch", requested + 1).addValue("offset", requestedOffset);

        List<SessionRow> rows = namedJdbc.query(sql.toString(), params, (rs, rowNum) -> new SessionRow(
                uuid(rs.getObject("id")), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("last_activity_at").toLocalDateTime()));
        boolean hasMore = rows.size() > requested;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, requested));
        Map<UUID, SessionMessageSummary> summaries = playgroundSummaries(rows.stream()
                .map(SessionRow::id).toList());
        List<ChatDtos.PlaygroundSessionResponse> sessions = rows.stream().map(row -> {
            SessionMessageSummary summary = summaries.getOrDefault(row.id(), new SessionMessageSummary(null, 0));
            String title = summary.title();
            if (title == null || title.isBlank()) title = row.createdAt().toLocalDate().toString();
            title = title.trim().substring(0, Math.min(60, title.trim().length()));
            return new ChatDtos.PlaygroundSessionResponse(row.id(), title, summary.count(), row.status(),
                    row.createdAt(), row.lastActivityAt());
        }).toList();
        String next = hasMore && !rows.isEmpty()
                ? encodeCursor("created".equals(requestedSort) ? rows.getLast().createdAt()
                        : rows.getLast().lastActivityAt(), rows.getLast().id()) : null;
        return new PlaygroundPage(sessions, next);
    }

    public void hidePlayground(UUID tenantId, UUID userId, UUID sessionId) {
        transactions.executeWithoutResult(status -> {
            ChatSession session = sessionRepository.findForUpdate(sessionId, tenantId)
                    .orElseThrow(() -> sessionNotFound());
            if (session.getChannel() != ChatChannel.EMPLOYEE_PLAYGROUND
                    || !Objects.equals(session.getUserId(), userId)) {
                throw sessionNotFound();
            }
            session.setHiddenAt(LocalDateTime.now());
        });
    }

    private void appendStatusDateAndTranscript(
            StringBuilder sql, MapSqlParameterSource params, String status, LocalDate from,
            LocalDate to, String query, String dateColumn, boolean rolesOnly
    ) {
        if (status != null) { sql.append(" AND s.status = :status"); params.addValue("status", status); }
        if (from != null) { sql.append(" AND ").append(dateColumn).append(" >= :dateFrom"); params.addValue("dateFrom", from.atStartOfDay()); }
        if (to != null) { sql.append(" AND ").append(dateColumn).append(" < :dateUntil"); params.addValue("dateUntil", to.plusDays(1).atStartOfDay()); }
        if (query != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.session_id = s.id");
            if (rolesOnly) sql.append(" AND m.role IN ('user', 'assistant')");
            sql.append(" AND lower(m.content) LIKE :pattern ESCAPE '\\')");
            params.addValue("pattern", likePattern(query));
        }
    }

    private Map<UUID, SessionMessageSummary> playgroundSummaries(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, SessionMessageSummary> result = new LinkedHashMap<>();
        namedJdbc.query("""
                SELECT session_id, role, content, sequence_number
                FROM chat_messages WHERE session_id IN (:ids)
                ORDER BY session_id, sequence_number
                """, new MapSqlParameterSource("ids", ids), rs -> {
            UUID id = uuid(rs.getObject("session_id"));
            SessionMessageSummary current = result.getOrDefault(id, new SessionMessageSummary(null, 0));
            String title = current.title();
            if (title == null && "user".equals(rs.getString("role"))) title = rs.getString("content");
            result.put(id, new SessionMessageSummary(title, current.count() + 1));
        });
        return result;
    }

    private String normalizeSearch(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 200) throw new ChatApiException(HttpStatus.BAD_REQUEST,
                "INVALID_SEARCH", "Search text must be 200 characters or fewer.");
        return normalized;
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ChatSessionStatus.valueOf(value.toUpperCase(Locale.ROOT)).name(); }
        catch (IllegalArgumentException exception) { throw invalidFilter("Status must be OPEN or CLOSED."); }
    }

    private String normalizePlaygroundSort(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("activity")) return "activity";
        if (value.equalsIgnoreCase("created") || value.equalsIgnoreCase("creation")) return "created";
        throw invalidFilter("Sort must be activity or created.");
    }

    private String normalizeDirection(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("desc")) return "desc";
        if (value.equalsIgnoreCase("asc")) return "asc";
        throw invalidFilter("Direction must be asc or desc.");
    }

    private void validateDates(LocalDate from, LocalDate to, String label) {
        if (from != null && to != null && from.isAfter(to))
            throw invalidFilter(label + " start date must not be after end date.");
    }

    private ChatApiException invalidFilter(String message) {
        return new ChatApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", message);
    }

    private String likePattern(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String encodeCursor(LocalDateTime timestamp, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (timestamp + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private CursorValue decodeCursor(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            return new CursorValue(LocalDateTime.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw invalidFilter("Cursor is invalid.");
        }
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    public record PlaygroundPage(List<ChatDtos.PlaygroundSessionResponse> sessions, String nextCursor) {}
    private record CursorValue(LocalDateTime timestamp, UUID id) {}
    private record SessionRow(UUID id, String status, LocalDateTime createdAt, LocalDateTime lastActivityAt) {}
    private record SessionMessageSummary(String title, long count) {}

    private GenerationContext captureContext(
            ChatSession session, ChatTurn turn, ChatMessage userMessage, String requestId) {
        return captureContext(session, turn, userMessage,
                firstPriorMessages(session.getId(), userMessage.getSequenceNumber()), requestId);
    }

    private GenerationContext captureContext(
            ChatSession session, ChatTurn turn, ChatMessage userMessage,
            List<ChatMessage> prior, String requestId) {
        var workspace = tenantWorkspaceApi.requireActiveKnowledgeBase(
                session.getTenantId(), session.getKnowledgeBaseId());
        List<AiInferenceApi.PriorMessage> history = prior.stream().limit(20)
                .map(message -> new AiInferenceApi.PriorMessage(message.getRole(), message.getContent()))
                .toList();
        return new GenerationContext(
                turn.getGenerationId(), turn.getId(), session.getTenantId(), session.getChatbotId(),
                session.getKnowledgeBaseId(), workspace.searchRevision(), session.getChannel().name(),
                session.getLocale(), userMessage.getContent(), history, workspace.tenantName(),
                requestId == null ? UUID.randomUUID().toString() : requestId);
    }

    private List<ChatMessage> firstPriorMessages(UUID sessionId, int beforeSequence) {
        return messageRepository.findBySessionIdAndSequenceNumberLessThanOrderBySequenceNumberAsc(
                sessionId, beforeSequence, PageRequest.of(0, 20));
    }

    private void validateCitations(
            ChatSession session, List<ChatDtos.CitationResponse> citations) {
        if (citations.isEmpty()) {
            return;
        }
        List<UUID> citationIds;
        try {
            citationIds = citations.stream().map(ChatDtos.CitationResponse::documentId)
                    .map(UUID::fromString).distinct().toList();
        } catch (IllegalArgumentException exception) {
            throw invalidCitation();
        }
        try {
            documentApi.validateCitations(session.getTenantId(), session.getKnowledgeBaseId(),
                    citationIds);
        } catch (RuntimeException exception) {
            throw invalidCitation();
        }
    }

    private ChatSession lockedAuthorizedSession(UUID tenantId, UUID userId, UUID sessionId) {
        ChatSession session = sessionRepository.findForUpdate(sessionId, tenantId)
                .orElseThrow(() -> sessionNotFound());
        authorizeSession(session, userId);
        if (session.getStatus() != ChatSessionStatus.OPEN || session.getHiddenAt() != null) {
            throw sessionNotFound();
        }
        return session;
    }

    private ChatSession authorizedSession(UUID tenantId, UUID userId, UUID sessionId) {
        ChatSession session = sessionRepository.findByIdAndTenantIdAndHiddenAtIsNull(sessionId, tenantId)
                .orElseThrow(() -> sessionNotFound());
        authorizeSession(session, userId);
        return session;
    }

    private void authorizeSession(ChatSession session, UUID userId) {
        if (session.getChannel() != ChatChannel.EMPLOYEE_PLAYGROUND
                || !Objects.equals(userId, session.getUserId())) {
            throw sessionNotFound();
        }
    }

    private int allocateSequence(ChatSession session) {
        int value = session.getNextSequenceNumber();
        session.setNextSequenceNumber(value + 1);
        session.setLastActivityAt(LocalDateTime.now());
        return value;
    }

    private ChatDtos.SessionResponse toSession(ChatSession session) {
        return new ChatDtos.SessionResponse(
                session.getId().toString(), session.getChatbotId().toString(),
                session.getKnowledgeBaseId().toString(), session.getTenantId().toString(),
                session.getLocale());
    }

    private ChatDtos.AssistantMessageResponse toAssistant(ChatMessage message, ChatSession session) {
        return new ChatDtos.AssistantMessageResponse(
                message.getRole(), message.getContent(), citations(message), null);
    }

    private ChatDtos.MessageResponse toMessage(ChatMessage message, ChatSession session) {
        return new ChatDtos.MessageResponse(
                message.getRole(), message.getContent(), citations(message),
                message.getSequenceNumber(), null);
    }

    private List<ChatDtos.CitationResponse> citations(ChatMessage message) {
        return message.getCitations().stream()
                .map(value -> objectMapper.convertValue(value, ChatDtos.CitationResponse.class)).toList();
    }

    private Map<String, Object> citationMap(ChatDtos.CitationResponse citation) {
        return objectMapper.convertValue(citation, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private String fingerprint(String content, Map<String, Object> metadata, String locale) {
        Map<String, Object> value = fingerprintPayload(content, metadata, locale);
        try {
            return sha256(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Message metadata is not serializable", exception);
        }
    }

    public static Map<String, Object> fingerprintPayload(
            String content, Map<String, Object> metadata, String locale) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("content", content);
        value.put("metadata", metadata == null ? Map.of() : new java.util.TreeMap<>(metadata));
        if (locale != null) {
            value.put("locale", locale);
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ChatApiException sessionNotFound() {
        return new ChatApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                "Chat session was not found.");
    }

    private ChatApiException invalidCitation() {
        return new ChatApiException(HttpStatus.BAD_GATEWAY, "INVALID_CITATION",
                "The inference service returned an invalid citation.");
    }

    private record PreparedTurn(
            UUID turnId,
            GenerationContext context,
            ChatDtos.AssistantMessageResponse replayed
    ) {
    }

    private record FinalizeResult(
            boolean revisionChanged,
            ChatDtos.AssistantMessageResponse response
    ) {
    }

    private record GenerationContext(
            UUID generationId,
            UUID turnId,
            UUID tenantId,
            UUID chatbotId,
            UUID knowledgeBaseId,
            long revision,
            String channel,
            String locale,
            String question,
            List<AiInferenceApi.PriorMessage> priorMessages,
            String tenantName,
            String requestId
    ) {
        AiInferenceApi.GenerationRequest toRequest() {
            return new AiInferenceApi.GenerationRequest(
                    generationId, turnId, tenantId, chatbotId, knowledgeBaseId, revision,
                    channel, locale, question, priorMessages, tenantName,
                    PROMPT_SCHEMA_VERSION, requestId, requestId);
        }

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("generation_id", generationId.toString());
            value.put("tenant_id", tenantId.toString());
            value.put("chatbot_id", chatbotId.toString());
            value.put("knowledge_base_id", knowledgeBaseId.toString());
            value.put("revision", revision);
            value.put("channel", channel);
            value.put("locale", locale);
            value.put("question", question);
            value.put("prior_messages", priorMessages.stream()
                    .map(message -> Map.of("role", message.role(), "content", message.content())).toList());
            value.put("tenant_name", tenantName);
            value.put("prompt_schema_version", PROMPT_SCHEMA_VERSION);
            return value;
        }
    }
}
