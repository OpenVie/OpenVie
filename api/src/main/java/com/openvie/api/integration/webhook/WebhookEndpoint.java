package com.openvie.api.integration.webhook;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One signed-event push target. Secrets are write-only: the response carries
 * the plaintext once, storage keeps only the encrypted form.
 */
@Getter
@Setter
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "secret_encrypted")
    private String secretEncrypted;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /** Optional whitelist of event types; null receives everything. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events")
    private List<String> events = new ArrayList<>();

    /** True when the endpoint accepts every event type. */
    public boolean acceptsEventType(String eventType) {
        return events == null || events.isEmpty() || events.contains(eventType);
    }
}
