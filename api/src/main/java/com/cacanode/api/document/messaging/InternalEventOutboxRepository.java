package com.cacanode.api.document.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface InternalEventOutboxRepository extends JpaRepository<InternalEventOutbox, UUID> {
    @Query(value = """
            SELECT * FROM internal_event_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= NOW()
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<InternalEventOutbox> lockDueEvents();
}
