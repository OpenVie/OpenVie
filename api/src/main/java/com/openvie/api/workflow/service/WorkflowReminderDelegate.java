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
 * BPMN service task "Record in-app reminder": the 24h reminder boundary of a
 * level 1 task. Fired by the async executor when the organization's reminder
 * SLA elapses; the actionable delivery to the workspace's notification
 * channels is a follow-up integration (issue #7 demo wiring).
 */
@Component("workflowReminderDelegate")
@RequiredArgsConstructor
@Slf4j(topic = "WORKFLOW")
public class WorkflowReminderDelegate implements JavaDelegate {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void execute(DelegateExecution execution) {
        UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));
        eventPublisher.publishEvent(AuditLogEvent.builder(this)
                .tenantId(workspaceId)
                .action(LogAction.WORKFLOW_REMINDER_SENT)
                .resourceType("workflow_process")
                .resourceId(UUID.fromString(execution.getProcessInstanceId()))
                .metadata(Map.of(
                        "documentId", String.valueOf(execution.getVariable("documentId")),
                        "taskName", String.valueOf(execution.getVariable("documentFileName")),
                        "policyVersion", String.valueOf(execution.getVariable("policyVersion")),
                        "approvalLevel", String.valueOf(execution.getVariable("approvalLevel"))))
                .build());
        log.info("Reminder recorded for process {}", execution.getProcessInstanceId());
    }
}
