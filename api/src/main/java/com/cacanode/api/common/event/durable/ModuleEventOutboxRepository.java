package com.cacanode.api.common.event.durable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface ModuleEventOutboxRepository extends JpaRepository<ModuleEventOutbox, UUID> {
    @Query(value = """
            SELECT * FROM module_event_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY created_at
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ModuleEventOutbox> lockDueBatch();

    long countByStatus(ModuleEventStatus status);

    Optional<ModuleEventOutbox> findTopByStatusOrderByCreatedAtAsc(ModuleEventStatus status);
}
