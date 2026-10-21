package com.openvie.api.common.repository;

import com.openvie.api.common.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Every audited event of one resource, in creation order. Workflow history
     * (issue #8) reads this for the {@code workflow_process} audit trail of a
     * process instance: who submitted, claimed, approved, rejected, or was
     * reminded — and when.
     */
    List<AuditLog> findByTenantIdAndResourceIdOrderByIdAsc(UUID tenantId, UUID resourceId);
}
