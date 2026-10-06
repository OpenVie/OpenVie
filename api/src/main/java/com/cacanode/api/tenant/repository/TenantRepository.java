package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.enums.WorkspaceVisibility;
import com.cacanode.api.tenant.model.Tenant;
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
