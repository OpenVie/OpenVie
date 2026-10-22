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

package com.openvie.api.tenant.repository;

import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import java.util.UUID;
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsByOrganization_IdAndSlug(UUID orgId, String slug);

    Optional<Tenant> findByOrganization_IdAndSlug(UUID orgId, String slug);

    List<Tenant> findByOrganization_IdOrderByCreatedAtAsc(UUID orgId);

    List<Tenant> findByOrganization_IdAndVisibilityOrderByCreatedAtAsc(
            UUID orgId, WorkspaceVisibility visibility);
    List<Tenant> findByOrganization_IdAndDefaultWorkspaceTrue(UUID orgId);


    long countByOrganization_Id(UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :tenantId")
    Optional<Tenant> findByIdForUpdate(@Param("tenantId") UUID tenantId);

}
