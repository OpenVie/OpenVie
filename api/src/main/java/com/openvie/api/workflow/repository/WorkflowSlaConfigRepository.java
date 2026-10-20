package com.openvie.api.workflow.repository;

import com.openvie.api.workflow.model.WorkflowSlaConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowSlaConfigRepository extends JpaRepository<WorkflowSlaConfig, UUID> {

    Optional<WorkflowSlaConfig> findByOrganizationId(UUID organizationId);
}
