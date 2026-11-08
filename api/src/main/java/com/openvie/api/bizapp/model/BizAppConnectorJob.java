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

package com.openvie.api.bizapp.model;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One durable {@code external_call} run: the hook or schedule trigger is
 * recorded immediately and a worker executes it after commit with retries.
 *
 * <p>Provider calls never run inside the writing transaction; the job row is
 * the only durable link between the record write and the outbound request, so
 * a crashed worker resumes from {@link #nextAttemptAt} instead of losing the
 * call.
 */
@Getter
@Setter
@Entity
@Table(name = "bizapp_connector_jobs")
public class BizAppConnectorJob extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "installation_id", nullable = false)
    private UUID installationId;

    @Column(name = "app_kind", nullable = false, length = 64)
    private String appKind;

    @Column(name = "entity", nullable = false, length = 64)
    private String entity;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "block_id", nullable = false, length = 64)
    private String blockId;

    /** {@code HOOK} or {@code SCHEDULE}. */
    @Column(name = "trigger_kind", nullable = false, length = 16)
    private String triggerKind;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "backoff_ms", nullable = false)
    private long backoffMs = 10_000L;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
