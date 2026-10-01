package com.cacanode.api.common.cache;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class BusinessCacheInvalidationPublisher {
    private final ApplicationEventPublisher publisher;

    public BusinessCacheInvalidationPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void workspace(UUID tenantId) {
        fixed(tenantId, BusinessCache.WORKSPACE);
    }

    public void memberMutation(UUID tenantId) {
        publisher.publishEvent(new BusinessCacheInvalidationEvent(tenantId, Set.of(
                BusinessCache.USER_DIRECTORY)));
    }

    public void documentMutation(UUID tenantId, UUID knowledgeBaseId) {
        publisher.publishEvent(new DocumentListInvalidationEvent(tenantId, knowledgeBaseId));
    }

    private void fixed(UUID tenantId, BusinessCache cache) {
        publisher.publishEvent(new BusinessCacheInvalidationEvent(tenantId, Set.of(cache)));
    }
}
