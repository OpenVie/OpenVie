package com.cacanode.api.common.event;

import com.cacanode.api.common.enums.LogAction;
import lombok.Getter;

import java.util.Map;
import java.util.UUID;

@Getter
public class AuditLogEvent extends org.springframework.context.ApplicationEvent {

    private final UUID tenantId;
    private final UUID userId;
    private final LogAction action;
    private final String resourceType;
    private final UUID resourceId;
    private final String ipAddress;
    private final String userAgent;
    private final Map<String, Object> metadata;

    public AuditLogEvent(Object source,
                         UUID tenantId,
                         UUID userId,
                         LogAction action,
                         String resourceType,
                         UUID resourceId,
                         String ipAddress,
                         String userAgent,
                         Map<String, Object> metadata) {
        super(source);
        this.tenantId = tenantId;
        this.userId = userId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.metadata = metadata;
    }

    public static AuditLogEventBuilder builder(Object source) {
        return new AuditLogEventBuilder(source);
    }

    public static class AuditLogEventBuilder {
        private final Object source;
        private UUID tenantId;
        private UUID userId;
        private LogAction action;
        private String resourceType;
        private UUID resourceId;
        private String ipAddress;
        private String userAgent;
        private Map<String, Object> metadata;

        public AuditLogEventBuilder(Object source) {
            this.source = source;
        }

        public AuditLogEventBuilder tenantId(UUID tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public AuditLogEventBuilder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public AuditLogEventBuilder action(LogAction action) {
            this.action = action;
            return this;
        }

        public AuditLogEventBuilder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public AuditLogEventBuilder resourceId(UUID resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public AuditLogEventBuilder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public AuditLogEventBuilder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public AuditLogEventBuilder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public AuditLogEvent build() {
            return new AuditLogEvent(source, tenantId, userId, action, resourceType,
                    resourceId, ipAddress, userAgent, metadata);
        }
    }
}
