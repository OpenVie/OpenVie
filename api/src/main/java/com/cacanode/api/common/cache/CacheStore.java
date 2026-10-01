package com.cacanode.api.common.cache;

import java.time.Duration;

public interface CacheStore {

    CacheReadResult get(String cacheName, String fullKey);

    CacheOperationStatus put(String cacheName, String fullKey, byte[] value, Duration baseTtl);

    CacheOperationStatus delete(String cacheName, String fullKey);
}
