package com.cacanode.api.chat.service;

import com.cacanode.api.chat.query.ChatControlPlaneService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatListQueryIntegrationTest {
    @Autowired ChatControlPlaneService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void playgroundCursorDoesNotSkipOrDuplicateAndTranscriptSearchIsLiteral() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<UUID> inserted = new ArrayList<>();
        LocalDateTime activity = LocalDateTime.parse("2026-07-20T10:00:00");
        for (int index = 0; index < 105; index++) {
            UUID sessionId = UUID.randomUUID();
            inserted.add(sessionId);
            insertSession(sessionId, tenantId, userId, "EMPLOYEE_PLAYGROUND", "OPEN",
                    activity.minusMinutes(index));
            insertMessage(sessionId, tenantId, index == 7 ? "literal 100%_ marker" : "question " + index);
        }
        UUID otherUserSession = UUID.randomUUID();
        insertSession(otherUserSession, tenantId, UUID.randomUUID(), "EMPLOYEE_PLAYGROUND", "OPEN", activity.plusMinutes(1));
        insertMessage(otherUserSession, tenantId, "question outside owner");

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            var page = service.playgroundPage(tenantId, userId, 30, 0, cursor,
                    null, null, null, null, "activity", "desc");
            seen.addAll(page.sessions().stream().map(item -> item.id()).toList());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertEquals(105, seen.size());
        assertEquals(105, new HashSet<>(seen).size());
        assertEquals(new HashSet<>(inserted), new HashSet<>(seen));

        var literal = service.playgroundPage(tenantId, userId, 30, 0, null,
                "100%_", null, null, null, "activity", "desc");
        assertEquals(1, literal.sessions().size());
        assertNull(literal.nextCursor());
    }

    private void insertSession(UUID id, UUID tenantId, UUID userId, String channel,
                               String status, LocalDateTime activity) {
        jdbc.update("""
                INSERT INTO chat_sessions (
                    id, tenant_id, user_id, chatbot_id, knowledge_base_id, locale, status,
                    channel, customer_metadata, last_activity_at, next_sequence_number,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'en-US', ?, ?, '{}', ?, 2, ?, ?)
                """, id, tenantId, userId, UUID.randomUUID(), UUID.randomUUID(), status, channel,
                Timestamp.valueOf(activity), Timestamp.valueOf(activity), Timestamp.valueOf(activity));
    }

    private void insertMessage(UUID sessionId, UUID tenantId, String content) {
        jdbc.update("""
                INSERT INTO chat_messages (
                    id, session_id, tenant_id, role, content, citations, sequence_number,
                    action, created_at
                ) VALUES (?, ?, ?, 'user', ?, '[]', 1, '{}', ?)
                """, UUID.randomUUID(), sessionId, tenantId, content,
                Timestamp.valueOf(LocalDateTime.parse("2026-07-20T10:00:00")));
    }
}
