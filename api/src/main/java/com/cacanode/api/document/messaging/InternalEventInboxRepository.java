package com.cacanode.api.document.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InternalEventInboxRepository extends JpaRepository<InternalEventInbox, UUID> {
}
