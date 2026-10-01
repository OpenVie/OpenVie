package com.cacanode.api.chat.model;

import com.cacanode.api.chat.enums.ChatTurnStatus;
import com.cacanode.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chat_turns")
public class ChatTurn extends BaseEntity {
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "generation_id", nullable = false, unique = true)
    private UUID generationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ChatTurnStatus status;

    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "user_message_id", nullable = false)
    private UUID userMessageId;

    @Column(name = "assistant_message_id")
    private UUID assistantMessageId;

    @Column(name = "knowledge_revision", nullable = false)
    private long knowledgeRevision;

    @Column(name = "generation_context", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> generationContext = new HashMap<>();

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_code", length = 100)
    private String failureCode;
}
