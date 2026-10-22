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

package com.openvie.api.common.event.durable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface ModuleEventOutboxRepository extends JpaRepository<ModuleEventOutbox, UUID> {
    /**
     * Due events: fresh work plus held invitations whose replay delay has
     * elapsed. NO_CHANNEL rows are re-locked so a channel enabled after the
     * fact drains them without operator action.
     */
    @Query(value = """
            SELECT * FROM module_event_outbox
            WHERE status IN ('PENDING', 'NO_CHANNEL') AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY created_at
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ModuleEventOutbox> lockDueBatch();

    long countByStatus(ModuleEventStatus status);

    Optional<ModuleEventOutbox> findTopByStatusOrderByCreatedAtAsc(ModuleEventStatus status);
}
