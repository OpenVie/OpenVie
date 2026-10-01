package com.cacanode.api.common.cache;

import java.util.Set;
import java.util.UUID;

public record BusinessCacheInvalidationEvent(UUID tenantId, Set<BusinessCache> caches) {
    public BusinessCacheInvalidationEvent {
        if (tenantId == null) throw new IllegalArgumentException("Tenant ID is required");
        caches = Set.copyOf(caches);
    }
}
