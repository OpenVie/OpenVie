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

package com.openvie.api.bizapp.repository;

import com.openvie.api.bizapp.model.BizAppRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BizAppRecordRepository extends JpaRepository<BizAppRecord, UUID> {

    Optional<BizAppRecord> findByIdAndTenantIdAndAppKind(UUID id, UUID tenantId, String appKind);

    Page<BizAppRecord> findByTenantIdAndAppKindAndEntityAndStatusOrderByUpdatedAtDesc(
            UUID tenantId, String appKind, String entity, String status, Pageable pageable);

    Page<BizAppRecord> findByTenantIdAndAppKindAndEntityOrderByUpdatedAtDesc(
            UUID tenantId, String appKind, String entity, Pageable pageable);

    long countByTenantIdAndAppKindAndEntityAndStatus(UUID tenantId, String appKind, String entity, String status);

    java.util.List<BizAppRecord> findByTenantIdAndAppKind(UUID tenantId, String appKind);

    Optional<BizAppRecord> findByTenantIdAndAppKindAndEntityAndDedupeKey(
            UUID tenantId, String appKind, String entity, String dedupeKey);

    /**
     * Serializes concurrent connector sinks writing the same dedupe identity
     * for the rest of the transaction, so the unique index is never raced into
     * an aborted statement.
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT pg_advisory_xact_lock(CAST(hashtext(:key) AS bigint))", nativeQuery = true)
    void lockDedupeKey(@org.springframework.data.repository.query.Param("key") String key);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT * FROM bizapp_records
            WHERE app_kind = 'booking'
              AND entity = 'bookings'
              AND status = 'ACTIVE'
              AND (runtime ->> 'reminderSentAt') IS NULL
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    java.util.List<BizAppRecord> findDueBookingRemindersBatch(@org.springframework.data.repository.query.Param("limit") int limit);
}
