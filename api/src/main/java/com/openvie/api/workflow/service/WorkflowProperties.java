package com.openvie.api.workflow.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workflow defaults from {@code application.yaml}. Only SLA defaults live
 * here; per-organization overrides are persisted in {@code
 * workflow_sla_configs} through the API so demos can shorten timers to
 * minutes without touching configuration.
 */
@Component
@ConfigurationProperties(prefix = "openvie.workflow")
@Getter
@Setter
public class WorkflowProperties {

    /** Level 1 reminder timer default (ISO-8601 duration). */
    private String defaultReminderDuration = "PT24H";

    /** Level 1 escalation timer default (ISO-8601 duration). */
    private String defaultEscalationDuration = "PT48H";

    /** Upper bound for organization-managed SLA values, in minutes. */
    private int maxSlaMinutes = 20160;
}
