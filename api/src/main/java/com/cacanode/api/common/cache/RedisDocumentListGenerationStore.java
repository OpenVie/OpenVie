package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
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
