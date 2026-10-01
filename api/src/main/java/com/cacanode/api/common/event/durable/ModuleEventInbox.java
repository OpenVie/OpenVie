package com.cacanode.api.common.event.durable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@IdClass(ModuleEventInbox.Key.class)
@Table(name = "module_event_inbox")
public class ModuleEventInbox {
    @Id
    @Column(name = "consumer_name", length = 160)
    private String consumerName;

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Column(name = "event_type", nullable = false, length = 160)
    private String eventType;

    public record Key(String consumerName, UUID eventId) implements Serializable {
    }
}
