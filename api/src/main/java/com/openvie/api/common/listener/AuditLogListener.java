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

package com.openvie.api.common.listener;

import com.openvie.api.common.event.AuditLogEvent;
import com.openvie.api.common.model.AuditLog;
import com.openvie.api.common.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j(topic = "AUDIT-LISTENER")
@Component
@RequiredArgsConstructor
public class AuditLogListener {

    private final AuditLogRepository auditLogRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAuditLogEvent(AuditLogEvent event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTenantId(event.getTenantId());
            auditLog.setUserId(event.getUserId());
            auditLog.setAction(event.getAction());
            auditLog.setResourceType(event.getResourceType());
            auditLog.setResourceId(event.getResourceId());
            auditLog.setIpAddress(event.getIpAddress());
            auditLog.setUserAgent(event.getUserAgent());
            auditLog.setMetadata(event.getMetadata());

            auditLogRepository.save(auditLog);
            log.debug("Audit log saved: action={}, userId={}", event.getAction(), event.getUserId());
        } catch (Exception e) {
            log.error("Failed to save audit log: action={}, userId={} — {}",
                    event.getAction(), event.getUserId(), e.getMessage());
        }
    }
}
