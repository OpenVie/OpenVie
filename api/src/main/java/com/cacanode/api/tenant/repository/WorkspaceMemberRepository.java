package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.model.WorkspaceMember;
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
