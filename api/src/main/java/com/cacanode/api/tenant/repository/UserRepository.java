package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.UserStatus;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = "organization")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "organization")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByIdAndOrganization_Id(UUID id, UUID orgId);

    List<User> findByOrganization_IdOrderByFullNameAsc(UUID orgId);

    long countByOrganization_IdAndRoleAndStatus(UUID orgId, OrgRole role, UserStatus status);
}
