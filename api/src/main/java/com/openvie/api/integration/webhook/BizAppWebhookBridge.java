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

import com.openvie.api.common.event.BizAppLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges BizApp lifecycle events into the webhook outbox. Mirror of
 * {@link WebhookAuditBridge}: a pure translation step that subscribes after
 * the domain commit, so journaling hiccup-free delivery keeps BizApp
 * transactions clean.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "WEBHOOK-BRIDGE")
public class BizAppWebhookBridge {

    private final WebhookEventPublisher webhookEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBizAppEvent(BizAppLifecycleEvent event) {
        if (event.getTenantId() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (event.getSourceId() != null) {
            data.put("record_id", event.getSourceId().toString());
        }
        if (event.getData() != null) {
            data.putAll(event.getData());
        }
        webhookEventPublisher.publish(event.getTenantId(), event.getEventType(), event.getSourceId(), data);
    }
}
