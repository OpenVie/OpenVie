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

package com.openvie.api.document.listener;

import com.openvie.api.common.event.BizAppLifecycleEvent;
import com.openvie.api.document.messaging.LakehouseCdcPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Listens to BizApp lifecycle events and streams Change Data Capture (CDC) records
 * for bizapp_records to the Lakehouse streaming topic without blocking OLTP execution (Workstream K, K-3).
 *
 * <p>Never publishes bizapp_credentials.
 */
@Slf4j(topic = "LAKEHOUSE-BIZAPP-CDC")
@Component
@RequiredArgsConstructor
public class LakehouseBizAppCdcListener {

    private final LakehouseCdcPublisher lakehouseCdcPublisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBizAppEvent(BizAppLifecycleEvent event) {
        if (event == null || event.getTenantId() == null) {
            return;
        }
        String eventType = event.getEventType();
        if (eventType != null && eventType.startsWith("bizapp.") && event.getData() != null) {
            try {
                Map<String, Object> cdcData = new HashMap<>(event.getData());
                cdcData.put("org_id", event.getOrgId() != null ? event.getOrgId().toString() : null);
                cdcData.put("record_id", event.getSourceId() != null ? event.getSourceId().toString() : null);
                cdcData.put("event_type", eventType);
                cdcData.put("status", "ACTIVE");
                lakehouseCdcPublisher.publishCdcEvent(event.getTenantId(), "bizapp_records", cdcData);
                log.debug("Published bizapp record CDC event to lakehouse: tenant={} type={}",
                        event.getTenantId(), eventType);
            } catch (Exception e) {
                log.warn("Failed to forward bizapp record event to lakehouse CDC: type={} tenant={} — {}",
                        eventType, event.getTenantId(), e.getMessage());
            }
        }
    }
}
