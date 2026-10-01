package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.model.Invitation;
import com.cacanode.api.tenant.enums.InvitationStatus;
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
