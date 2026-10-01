package com.cacanode.api.chat.repository;

import com.cacanode.api.chat.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID sessionId, int after, Pageable pageable);

    List<ChatMessage> findBySessionIdOrderBySequenceNumberAsc(UUID sessionId);

    List<ChatMessage> findBySessionIdOrderBySequenceNumberDesc(UUID sessionId, Pageable pageable);

    List<ChatMessage> findBySessionIdAndSequenceNumberLessThanOrderBySequenceNumberAsc(
            UUID sessionId, int sequenceNumber, Pageable pageable);

    Optional<ChatMessage> findFirstBySessionIdAndRoleOrderBySequenceNumberAsc(UUID sessionId, String role);

    long countBySessionId(UUID sessionId);

    Optional<ChatMessage> findFirstBySessionIdAndSequenceNumberLessThanOrderBySequenceNumberDesc(
            UUID sessionId, int sequenceNumber);
}
