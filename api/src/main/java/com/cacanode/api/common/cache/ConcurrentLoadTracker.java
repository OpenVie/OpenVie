package com.cacanode.api.common.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local observation of authoritative loads. It never blocks a caller and retains only a
 * SHA-256 digest of the cache key for the lifetime of an active load.
 */
public final class ConcurrentLoadTracker {
    private final CacheMetrics metrics;
    private final ConcurrentHashMap<TrackedKey, Integer> active = new ConcurrentHashMap<>();

    public ConcurrentLoadTracker(CacheMetrics metrics) {
        this.metrics = metrics;
    }

    public Scope observe(String cacheName, String cacheKey) {
        TrackedKey trackedKey = new TrackedKey(cacheName, digest(cacheKey));
        int concurrency = active.compute(trackedKey, (ignored, current) -> current == null ? 1 : current + 1);
        boolean metricsStarted = false;
        try {
            metrics.authoritativeLoadStarted(cacheName, concurrency);
            metricsStarted = true;
        } catch (RuntimeException ignored) {
            // Observation must never change authoritative execution behavior.
        }
        return new Scope(trackedKey, metricsStarted);
    }

    int activeKeyCount() {
        return active.size();
    }

    Map<String, Integer> activeKeyHashes() {
        return active.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> entry.getKey().cacheName() + ":" + entry.getKey().keyHash(),
                Map.Entry::getValue));
    }

    private void finish(TrackedKey trackedKey, boolean metricsStarted) {
        active.computeIfPresent(trackedKey, (ignored, current) -> current <= 1 ? null : current - 1);
        if (metricsStarted) {
            try {
                metrics.authoritativeLoadFinished(trackedKey.cacheName());
            } catch (RuntimeException ignored) {
                // Observation must never change authoritative execution behavior.
            }
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record TrackedKey(String cacheName, String keyHash) {}

    public final class Scope implements AutoCloseable {
        private final TrackedKey trackedKey;
        private final boolean metricsStarted;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Scope(TrackedKey trackedKey, boolean metricsStarted) {
            this.trackedKey = trackedKey;
            this.metricsStarted = metricsStarted;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                finish(trackedKey, metricsStarted);
            }
        }
    }
}
