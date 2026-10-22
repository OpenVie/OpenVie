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

import com.openvie.api.tenant.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.UserStatus;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = "organization")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "organization")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "organization")
    Optional<User> findByKeycloakSubject(String keycloakSubject);

    Optional<User> findByIdAndOrganization_Id(UUID id, UUID orgId);

    List<User> findByOrganization_IdOrderByFullNameAsc(UUID orgId);

    long countByOrganization_IdAndRoleAndStatus(UUID orgId, OrgRole role, UserStatus status);

    /**
     * Active organization accounts matching a search fragment that are not yet
     * members of the given workspace — the add-member autocomplete pool.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.organization.id = :orgId
              AND u.status = :requiredStatus
              AND u.id <> :actorId
              AND (LOWER(u.email) LIKE :query OR LOWER(u.fullName) LIKE :query)
              AND NOT EXISTS (
                  SELECT m FROM WorkspaceMember m
                  WHERE m.user = u AND m.workspace.id = :workspaceId)
            ORDER BY u.fullName ASC
            """)
    List<User> findAddableCandidates(
            @Param("orgId") UUID orgId,
            @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId,
            @Param("query") String query,
            @Param("requiredStatus") UserStatus requiredStatus,
            Pageable limit);
}
