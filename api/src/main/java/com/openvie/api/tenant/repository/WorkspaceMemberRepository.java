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

import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.model.WorkspaceMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    Optional<WorkspaceMember> findByUser_IdAndWorkspace_Id(UUID userId, UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from WorkspaceMember m where m.user.id = :userId and m.workspace.id = :workspaceId")
    Optional<WorkspaceMember> findByUser_IdAndWorkspace_IdForUpdate(
            @Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    @Query("select m from WorkspaceMember m join fetch m.workspace where m.user.id = :userId")
    List<WorkspaceMember> findVisibleByUserId(@Param("userId") UUID userId);

    List<WorkspaceMember> findByWorkspace_IdOrderByRoleAscCreatedAtAsc(UUID workspaceId);

    long countByWorkspace_IdAndRole(UUID workspaceId, WorkspaceRole role);

    void deleteByUser_IdAndWorkspace_Id(UUID userId, UUID workspaceId);
}
