package com.openvie.api.workflow.model;

import com.openvie.api.common.model.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Organization-level SLA tuning for approval timers (issue #8): the 24h/48h
 * defaults stay in {@code application.yaml}; rows here exist only for
 * organizations that have overriden them, so the table stays small and the
 * workflow engine keeps reading plain process variables.
 */
@Getter
@Setter
@Entity
@Table(name = "workflow_sla_configs")
public class WorkflowSlaConfig extends BaseEntity {

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    /** Reminder timer for level 1 tasks, expressed in minutes. */
    @Column(name = "reminder_minutes", nullable = false)
    private Integer reminderMinutes;

    /** Escalation timer that moves the level 1 task to level 2, in minutes. */
    @Column(name = "escalation_minutes", nullable = false)
    private Integer escalationMinutes;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
