package com.cacanode.api.notification.model;

import com.cacanode.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An organization-level notification channel instance (for example an SMTP
 * relay or a provider API key). The channel implementations are in-repo beans
 * selected by {@code type}; this row supplies their credentials and whether
 * they are switched on.
 *
 * <p>The organization is referenced by id, not by entity: the notification
 * module must not import tenant internals, and no query here needs a join.
 *
 * <p>{@code credentialsEncrypted} holds ciphertext produced by the credential
 * cipher and is never returned by any API after creation.
 */
@Getter
@Setter
@Entity
@Table(
        name = "notification_channels",
        indexes = {
                @Index(name = "idx_notification_channel_org", columnList = "org_id,enabled")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_notification_channel_org_type", columnNames = {"org_id", "type"})
        }
)
public class NotificationChannel extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "credentials_encrypted", columnDefinition = "text")
    private String credentialsEncrypted;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "last_delivery_error")
    private String lastDeliveryError;

    @Column(name = "last_delivery_at")
    private LocalDateTime lastDeliveryAt;
}
