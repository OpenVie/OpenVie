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

import java.time.Duration;

@Component
public class RedisCacheStore implements CacheStore {

    private final RedisTemplate<String, byte[]> redisTemplate;
    private final CacheProperties properties;
    private final TtlJitter ttlJitter;
    private final CacheMetrics metrics;

    public RedisCacheStore(
            RedisTemplate<String, byte[]> redisTemplate,
            CacheProperties properties,
            TtlJitter ttlJitter,
            CacheMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.ttlJitter = ttlJitter;
        this.metrics = metrics;
    }

    @Override
    public CacheReadResult get(String cacheName, String fullKey) {
        long started = System.nanoTime();
        try {
            if (!properties.isEnabled()) {
                metrics.cacheOperation(cacheName, "bypass");
                return CacheReadResult.of(CacheReadStatus.BYPASS);
            }
            byte[] value = redisTemplate.opsForValue().get(fullKey);
            metrics.redisOperation("cache", "get", "success");
            if (value == null) {
                metrics.cacheOperation(cacheName, "miss");
                return CacheReadResult.of(CacheReadStatus.MISS);
            }
            metrics.cacheOperation(cacheName, "hit");
            metrics.payload(cacheName, value.length);
            return CacheReadResult.hit(value);
        } catch (RuntimeException exception) {
            metrics.redisOperation("cache", "get", "error");
            metrics.cacheOperation(cacheName, "error");
            return CacheReadResult.of(CacheReadStatus.ERROR);
        } finally {
            metrics.cacheDuration(cacheName, "get", System.nanoTime() - started);
        }
    }

    @Override
    public CacheOperationStatus put(
            String cacheName,
            String fullKey,
            byte[] value,
            Duration baseTtl
    ) {
        long started = System.nanoTime();
        try {
            if (!properties.isEnabled()) {
                metrics.cacheOperation(cacheName, "bypass");
                return CacheOperationStatus.BYPASS;
            }
            redisTemplate.opsForValue().set(fullKey, value, ttlJitter.apply(baseTtl));
            metrics.redisOperation("cache", "set", "success");
            metrics.cacheOperation(cacheName, "write");
            metrics.payload(cacheName, value.length);
            return CacheOperationStatus.SUCCESS;
        } catch (RuntimeException exception) {
            metrics.redisOperation("cache", "set", "error");
            metrics.cacheOperation(cacheName, "error");
            return CacheOperationStatus.ERROR;
        } finally {
            metrics.cacheDuration(cacheName, "put", System.nanoTime() - started);
        }
    }

    @Override
    public CacheOperationStatus delete(String cacheName, String fullKey) {
        long started = System.nanoTime();
        try {
            if (!properties.isEnabled()) {
                metrics.cacheOperation(cacheName, "bypass");
                return CacheOperationStatus.BYPASS;
            }
            redisTemplate.delete(fullKey);
            metrics.redisOperation("cache", "delete", "success");
            metrics.cacheOperation(cacheName, "invalidate");
            return CacheOperationStatus.SUCCESS;
        } catch (RuntimeException exception) {
            metrics.redisOperation("cache", "delete", "error");
            metrics.cacheOperation(cacheName, "error");
            return CacheOperationStatus.ERROR;
        } finally {
            metrics.cacheDuration(cacheName, "delete", System.nanoTime() - started);
        }
    }
}
