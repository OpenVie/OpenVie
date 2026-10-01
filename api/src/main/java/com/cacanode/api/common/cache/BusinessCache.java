package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;

import java.time.Duration;

public enum BusinessCache {
    WORKSPACE("workspace") {
        public boolean domainEnabled(CacheProperties p) { return p.isWorkspaceEnabled(); }
        public int ttlSeconds(CacheProperties p) { return p.getWorkspaceTtlSeconds(); }
    },
    USER_DIRECTORY("user-directory") {
        public boolean domainEnabled(CacheProperties p) { return p.isUserDirectoryEnabled(); }
        public int ttlSeconds(CacheProperties p) { return p.getUserDirectoryTtlSeconds(); }
    },
    DOCUMENT_LIST("document-list") {
        public boolean domainEnabled(CacheProperties p) { return p.isDocumentListEnabled(); }
        public int ttlSeconds(CacheProperties p) { return p.getDocumentListTtlSeconds(); }
    };

    private final String label;

    BusinessCache(String label) {
        this.label = label;
    }

    public String label() { return label; }
    public abstract boolean domainEnabled(CacheProperties properties);
    public abstract int ttlSeconds(CacheProperties properties);

    public boolean enabled(CacheProperties properties) {
        return properties.isEnabled() && properties.isBusinessReadEnabled() && domainEnabled(properties);
    }

    public Duration ttl(CacheProperties properties) {
        return Duration.ofSeconds(Math.max(1, ttlSeconds(properties)));
    }
}
