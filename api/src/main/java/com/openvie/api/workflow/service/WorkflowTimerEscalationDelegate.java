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

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * BPMN service task "Audit SLA escalation": fired by the interrupting 48h
 * boundary of a level 1 task. Moves the overdue task onward — the flow then
 * lands on the level 2 (ORG_OWNER) approval task — and records the overdue
 * escalation in the audit log.
 */
@Component("workflowTimerEscalationDelegate")
@RequiredArgsConstructor
@Slf4j(topic = "WORKFLOW")
public class WorkflowTimerEscalationDelegate implements JavaDelegate {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void execute(DelegateExecution execution) {
        UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));
        execution.setVariable("escalated", true);

        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(workspaceId)
                .action(LogAction.WORKFLOW_ESCALATED)
                .resourceType("workflow_process")
                .resourceId(UUID.fromString(execution.getProcessInstanceId()))
                .metadata(Map.of(
                        "overdue", true,
                        "manual", false,
                        "reason", "SLA overdue — the level 1 task was escalated to level 2",
                        "documentId", String.valueOf(execution.getVariable("documentId")),
                        "policyVersion", String.valueOf(execution.getVariable("policyVersion")),
                        "approvalLevel", String.valueOf(execution.getVariable("approvalLevel"))))
                .build());
        log.info("SLA escalation recorded for process {}", execution.getProcessInstanceId());
    }
}
