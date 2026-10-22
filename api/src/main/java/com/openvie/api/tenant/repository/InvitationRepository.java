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

import com.openvie.api.tenant.model.Invitation;
import com.openvie.api.tenant.api.InvitationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    List<Invitation> findByTenant_IdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Invitation> findByIdAndTenant_Id(UUID id, UUID tenantId);

    Optional<Invitation> findFirstByTenant_IdAndEmailIgnoreCaseAndStatus(
            UUID tenantId, String email, InvitationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i join fetch i.tenant join fetch i.invitedBy " +
            "where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Query("select i from Invitation i join fetch i.tenant where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHash(@Param("tokenHash") String tokenHash);

    boolean existsByTenant_IdAndEmailIgnoreCaseAndStatusAndExpiresAtAfter(
            UUID tenantId, String email, InvitationStatus status, LocalDateTime now);

    long countByTenant_IdAndStatusAndExpiresAtAfter(UUID tenantId, InvitationStatus status, LocalDateTime now);
}
