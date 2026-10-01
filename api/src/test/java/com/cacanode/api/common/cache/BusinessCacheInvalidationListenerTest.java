package com.cacanode.api.common.cache;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessCacheInvalidationListenerTest {
    @Test
    void deletesOnlyExactTenantKeysAndIncrementsExactDocumentGeneration() {
        CacheStore store = mock(CacheStore.class);
        DocumentListGenerationStore generations = mock(DocumentListGenerationStore.class);
        CacheKeyFactory keys = new CacheKeyFactory("ccn:v1");
        BusinessCacheInvalidationListener listener = new BusinessCacheInvalidationListener(store, keys, generations);
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();

        listener.invalidate(new BusinessCacheInvalidationEvent(tenantId, Set.of(
                BusinessCache.WORKSPACE, BusinessCache.USER_DIRECTORY)));
        listener.invalidateDocuments(new DocumentListInvalidationEvent(tenantId, knowledgeBaseId));

        verify(store).delete("workspace", "ccn:v1:workspace:tenant:" + tenantId);
        verify(store).delete("user-directory", "ccn:v1:user-directory:tenant:" + tenantId);
        verify(generations).increment(tenantId, knowledgeBaseId);
    }
}
