package com.cacanode.api.common.listener;

import com.cacanode.api.common.event.AuditLogEvent;
import com.cacanode.api.common.model.AuditLog;
import com.cacanode.api.common.repository.AuditLogRepository;
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
