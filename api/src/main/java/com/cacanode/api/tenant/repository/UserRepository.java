package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = "tenant")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "tenant")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByIdAndTenant_Id(UUID id, UUID tenantId);

    List<User> findByTenant_IdOrderByFullNameAsc(UUID tenantId);

    long countByTenant_IdAndRoleAndStatus(UUID tenantId, UserRole role, UserStatus status);

    long countByTenant_IdAndStatus(UUID tenantId, UserStatus status);

}
