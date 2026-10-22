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

import com.openvie.api.common.event.AuditLogEvent;
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
 * Listens to domain audit events and streams Change Data Capture (CDC) records
 * to the Lakehouse streaming topic without blocking primary OLTP execution.
 */
@Slf4j(topic = "LAKEHOUSE-CDC-LISTENER")
@Component
@RequiredArgsConstructor
public class LakehouseAuditCdcListener {

    private final LakehouseCdcPublisher lakehouseCdcPublisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditLogEvent(AuditLogEvent event) {
        if (event == null || event.getTenantId() == null) {
            return;
        }
        try {
            Map<String, Object> cdcData = new HashMap<>();
            cdcData.put("user_id", event.getUserId() != null ? event.getUserId().toString() : null);
            cdcData.put("action", event.getAction() != null ? event.getAction().name() : null);
            cdcData.put("resource_type", event.getResourceType());
            cdcData.put("resource_id", event.getResourceId() != null ? event.getResourceId().toString() : null);
            cdcData.put("ip_address", event.getIpAddress());
            cdcData.put("user_agent", event.getUserAgent());
            lakehouseCdcPublisher.publishCdcEvent(event.getTenantId(), "audit_events", cdcData);
        } catch (Exception e) {
            log.warn("Failed to forward audit event to lakehouse CDC: action={} tenant={} — {}",
                    event.getAction(), event.getTenantId(), e.getMessage());
        }
    }
}
