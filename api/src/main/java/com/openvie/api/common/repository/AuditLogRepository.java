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

package com.openvie.api.common.repository;

import com.openvie.api.common.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Every audited event of one resource, in creation order. Workflow history
     * (issue #8) reads this for the {@code workflow_process} audit trail of a
     * process instance: who submitted, claimed, approved, rejected, or was
     * reminded — and when.
     */
    List<AuditLog> findByTenantIdAndResourceIdOrderByIdAsc(UUID tenantId, UUID resourceId);
}
