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

import com.openvie.api.bizapp.model.BizAppConnectorJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BizAppConnectorJobRepository extends JpaRepository<BizAppConnectorJob, UUID> {

    /**
     * Due job ids in one atomic read: {@code SKIP LOCKED} keeps concurrent
     * workers from claiming the same row without serializing the whole queue.
     */
    @Query(value = "SELECT id FROM bizapp_connector_jobs WHERE status = 'PENDING' AND next_attempt_at <= now() "
            + "ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> claimDueIds(@Param("limit") int limit);

    Optional<BizAppConnectorJob> findFirstByInstallationIdAndBlockIdAndRecordIdOrderByCreatedAtDesc(
            UUID installationId, String blockId, UUID recordId);
}
