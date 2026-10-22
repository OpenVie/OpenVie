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

package com.openvie.api.common.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BusinessCacheInvalidationListener {
    private final CacheStore cacheStore;
    private final CacheKeyFactory keyFactory;
    private final DocumentListGenerationStore generationStore;

    public BusinessCacheInvalidationListener(
            CacheStore cacheStore,
            CacheKeyFactory keyFactory,
            DocumentListGenerationStore generationStore
    ) {
        this.cacheStore = cacheStore;
        this.keyFactory = keyFactory;
        this.generationStore = generationStore;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(BusinessCacheInvalidationEvent event) {
        event.caches().forEach(cache -> cacheStore.delete(cache.label(), fixedKey(cache, event.tenantId().toString())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidateDocuments(DocumentListInvalidationEvent event) {
        generationStore.increment(event.tenantId(), event.knowledgeBaseId());
    }

    private String fixedKey(BusinessCache cache, String tenantId) {
        String domain = switch (cache) {
            case WORKSPACE -> "workspace";
            case USER_DIRECTORY -> "user-directory";
            case DOCUMENT_LIST -> throw new IllegalArgumentException("Cache does not use a fixed key");
        };
        return keyFactory.build(domain, "tenant", tenantId);
    }
}
