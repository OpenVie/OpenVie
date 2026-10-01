package com.cacanode.api.ai.repository;

import com.cacanode.api.ai.enums.ModelConfigStatus;
import com.cacanode.api.ai.model.ModelConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModelConfigVersionRepository extends JpaRepository<ModelConfigVersion, UUID> {
    Optional<ModelConfigVersion> findFirstByStatusOrderByCreatedAtDesc(ModelConfigStatus status);

    Optional<ModelConfigVersion> findByName(String name);
}
