// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

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
