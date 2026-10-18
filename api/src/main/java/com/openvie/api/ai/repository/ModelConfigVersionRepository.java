package com.openvie.api.ai.repository;

import com.openvie.api.ai.enums.ModelConfigStatus;
import com.openvie.api.ai.model.ModelConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModelConfigVersionRepository extends JpaRepository<ModelConfigVersion, UUID> {
    Optional<ModelConfigVersion> findFirstByStatusOrderByCreatedAtDesc(ModelConfigStatus status);

    Optional<ModelConfigVersion> findByName(String name);
}
