package com.cacanode.api.chat.repository;

import com.cacanode.api.chat.model.ChatTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, UUID> {
    Optional<ChatTurn> findBySessionIdAndIdempotencyKeyHash(UUID sessionId, String idempotencyKeyHash);
}
