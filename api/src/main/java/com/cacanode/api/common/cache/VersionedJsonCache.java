package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

@Component
public class VersionedJsonCache {
    private static final int SCHEMA_VERSION = 1;

    private final CacheStore store;
    private final CacheProperties properties;
    private final CacheMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ConcurrentLoadTracker concurrentLoads;

    @Autowired
    public VersionedJsonCache(
            CacheStore store,
            CacheProperties properties,
            CacheMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this(store, properties, metrics, objectMapper, new ConcurrentLoadTracker(metrics));
    }

    VersionedJsonCache(
            CacheStore store,
            CacheProperties properties,
            CacheMetrics metrics,
            ObjectMapper objectMapper,
            ConcurrentLoadTracker concurrentLoads
    ) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.concurrentLoads = concurrentLoads;
    }

    public <T> T getOrLoad(BusinessCache cache, String key, Class<T> valueType, Supplier<T> loader) {
        return getOrLoad(cache, key, objectMapper.getTypeFactory().constructType(valueType), loader);
    }

    public <T> T getOrLoad(BusinessCache cache, String key, JavaType valueType, Supplier<T> loader) {
        if (!cache.enabled(properties)) {
            metrics.cacheOperation(cache.label(), "bypass");
            return authoritative(cache, loader);
        }

        CacheReadResult result = store.get(cache.label(), key);
        if (result.status() == CacheReadStatus.HIT) {
            T cached = decode(cache, key, result.value(), valueType);
            if (cached != null) {
                return cached;
            }
        }

        T loaded = authoritative(cache, key, loader);
        if (loaded != null) {
            fillAfterCommit(cache, key, loaded);
        }
        return loaded;
    }

    public <T> T bypassAndLoad(BusinessCache cache, Supplier<T> loader) {
        metrics.cacheOperation(cache.label(), "bypass");
        return authoritative(cache, loader);
    }

    private <T> T authoritative(BusinessCache cache, Supplier<T> loader) {
        return timedAuthoritative(cache, loader);
    }

    private <T> T authoritative(BusinessCache cache, String key, Supplier<T> loader) {
        try (ConcurrentLoadTracker.Scope ignored = concurrentLoads.observe(cache.label(), key)) {
            return timedAuthoritative(cache, loader);
        }
    }

    private <T> T timedAuthoritative(BusinessCache cache, Supplier<T> loader) {
        long started = System.nanoTime();
        try {
            T value = loader.get();
            metrics.authoritativeDuration(cache.label(), value == null ? "not_found" : "success",
                    System.nanoTime() - started);
            return value;
        } catch (RuntimeException exception) {
            metrics.authoritativeDuration(cache.label(), "error", System.nanoTime() - started);
            throw exception;
        }
    }

    private <T> T decode(BusinessCache cache, String key, byte[] bytes, JavaType valueType) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (!root.isObject() || root.path("schema_version").asInt(-1) != SCHEMA_VERSION
                    || !root.has("payload") || root.get("payload").isNull()) {
                metrics.cacheOperation(cache.label(), "error");
                store.delete(cache.label(), key);
                return null;
            }
            return objectMapper.convertValue(root.get("payload"), valueType);
        } catch (RuntimeException | java.io.IOException exception) {
            metrics.cacheOperation(cache.label(), "error");
            store.delete(cache.label(), key);
            return null;
        }
    }

    private void fillAfterCommit(BusinessCache cache, String key, Object value) {
        final byte[] encoded;
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("schema_version", SCHEMA_VERSION);
            envelope.set("payload", objectMapper.valueToTree(value));
            encoded = objectMapper.writeValueAsBytes(envelope);
        } catch (RuntimeException | java.io.IOException exception) {
            metrics.cacheOperation(cache.label(), "error");
            return;
        }

        Runnable fill = () -> store.put(cache.label(), key, encoded, cache.ttl(properties));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fill.run();
                }
            });
        } else {
            fill.run();
        }
    }
}
