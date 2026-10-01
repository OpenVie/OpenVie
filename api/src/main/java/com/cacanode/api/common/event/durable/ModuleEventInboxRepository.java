package com.cacanode.api.common.event.durable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ModuleEventInboxRepository extends JpaRepository<ModuleEventInbox, ModuleEventInbox.Key> {
    @Modifying
    @Query(value = """
            INSERT INTO module_event_inbox (consumer_name, event_id, event_type, processed_at)
            VALUES (:consumerName, :eventId, :eventType, CURRENT_TIMESTAMP)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("consumerName") String consumerName,
              @Param("eventId") UUID eventId,
              @Param("eventType") String eventType);
}
