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

package com.openvie.api.integration.webhook;

import com.openvie.api.common.enums.LogAction;
import com.openvie.api.common.event.AuditLogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bridges workflow audit events into the webhook outbox. The audit event
 * already carries the full decision metadata, so the bridge is a pure
 * translation step: it subscribes after the domain commit, which keeps a
 * delivery-journaling hiccup from ever contaminating the workflow transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "WEBHOOK-BRIDGE")
public class WebhookAuditBridge {

    private static final Set<LogAction> INTEGRATION_ACTIONS = Set.of(
            LogAction.WORKFLOW_STARTED,
            LogAction.WORKFLOW_APPROVED,
            LogAction.WORKFLOW_REJECTED,
            LogAction.WORKFLOW_ESCALATED);

    private final WebhookEventPublisher webhookEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEvent(AuditLogEvent event) {
        if (!INTEGRATION_ACTIONS.contains(event.getAction()) || event.getTenantId() == null) {
            return;
        }
        String eventType = switch (event.getAction()) {
            case WORKFLOW_STARTED -> "workflow.started";
            case WORKFLOW_APPROVED -> "workflow.approved";
            case WORKFLOW_REJECTED -> "workflow.rejected";
            default -> "workflow.escalated";
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("process_id", event.getResourceId() == null ? null : event.getResourceId().toString());
        data.put("action", event.getAction().name());
        if (event.getMetadata() != null) {
            data.putAll(event.getMetadata());
        }
        webhookEventPublisher.publish(event.getTenantId(), eventType, event.getResourceId(), data);
    }
}
