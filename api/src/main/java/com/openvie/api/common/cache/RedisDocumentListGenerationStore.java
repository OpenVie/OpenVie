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

import com.openvie.api.common.config.CacheProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.UUID;

@Component
public class RedisDocumentListGenerationStore implements DocumentListGenerationStore {
    private final RedisTemplate<String, byte[]> redisTemplate;
    private final CacheKeyFactory keyFactory;
    private final CacheProperties properties;
    private final CacheMetrics metrics;

    public RedisDocumentListGenerationStore(
            RedisTemplate<String, byte[]> redisTemplate,
            CacheKeyFactory keyFactory,
            CacheProperties properties,
            CacheMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.keyFactory = keyFactory;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public OptionalLong current(UUID tenantId, UUID knowledgeBaseId) {
        if (!BusinessCache.DOCUMENT_LIST.enabled(properties)) {
            return OptionalLong.empty();
        }
        try {
            byte[] value = redisTemplate.opsForValue().get(key(tenantId, knowledgeBaseId));
            metrics.redisOperation("cache", "generation-get", "success");
            return OptionalLong.of(value == null ? 0 : Long.parseLong(new String(value, StandardCharsets.US_ASCII)));
        } catch (RuntimeException exception) {
            metrics.redisOperation("cache", "generation-get", "error");
            metrics.cacheOperation(BusinessCache.DOCUMENT_LIST.label(), "error");
            return OptionalLong.empty();
        }
    }

    @Override
    public CacheOperationStatus increment(UUID tenantId, UUID knowledgeBaseId) {
        if (!BusinessCache.DOCUMENT_LIST.enabled(properties)) {
            return CacheOperationStatus.BYPASS;
        }
        try {
            redisTemplate.opsForValue().increment(key(tenantId, knowledgeBaseId));
            metrics.redisOperation("cache", "generation-increment", "success");
            metrics.cacheOperation(BusinessCache.DOCUMENT_LIST.label(), "invalidate");
            return CacheOperationStatus.SUCCESS;
        } catch (RuntimeException exception) {
            metrics.redisOperation("cache", "generation-increment", "error");
            metrics.cacheOperation(BusinessCache.DOCUMENT_LIST.label(), "error");
            return CacheOperationStatus.ERROR;
        }
    }

    private String key(UUID tenantId, UUID knowledgeBaseId) {
        return keyFactory.build("documents-generation", "tenant", tenantId.toString(),
                "kb", knowledgeBaseId.toString());
    }
}
