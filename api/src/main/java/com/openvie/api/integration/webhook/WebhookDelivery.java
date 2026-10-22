// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.integration.webhook;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional webhook outbox row: one per (event, endpoint). Inserted inside
 * the same transaction as the domain change, delivered asynchronously with
 * bounded retries and an explicit DEAD terminal status.
 */
@Getter
@Setter
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery extends BaseEntity {

    public static final int MAX_ATTEMPTS = 5;

    /** Stable per event across its endpoints — receiver-side dedupe key. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Workspace the event happened in; null for org-level events. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
}
