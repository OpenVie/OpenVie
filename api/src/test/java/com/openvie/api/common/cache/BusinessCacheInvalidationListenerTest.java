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
