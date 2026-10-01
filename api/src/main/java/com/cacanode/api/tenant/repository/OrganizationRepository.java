package com.cacanode.api.tenant.repository;

import com.cacanode.api.tenant.model.Organization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsBySlug(String slug);

    /**
     * Serializes concurrent setup submissions: the first transaction takes a
     * transaction-scoped advisory lock, the second blocks, then observes the
     * committed count and refuses. The key is an arbitrary fixed namespace for
     * this application.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext('openvie_setup'))", nativeQuery = true)
    void lockSetup();
}
