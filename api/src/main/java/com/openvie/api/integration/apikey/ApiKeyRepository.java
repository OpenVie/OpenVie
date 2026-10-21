package com.openvie.api.integration.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, String status);

    List<ApiKey> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    boolean existsByUserId(UUID userId);

    /** Time-throttled usage tracking needs rows not touched in the last 30s. */
    List<ApiKey> findByUserIdAndLastUsedAtBeforeOrLastUsedAtIsNull(UUID userId, LocalDateTime threshold);
}
