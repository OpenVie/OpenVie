package com.cacanode.api.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CacheMetrics {

    private static final String SERVICE = "spring-api";
    private static final Set<String> CACHE_NAMES = Set.of(
            "foundation", "business-read", "embedding", "retrieval",
            "workspace", "user-directory", "document-list"
    );
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicInteger> authoritativeLoadsInFlight =
            new ConcurrentHashMap<>();

    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void cacheOperation(String cacheName, String outcome) {
        Counter.builder("cacanode.cache.operations")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void cacheDuration(String cacheName, String operation, long elapsedNanos) {
        Timer.builder("cacanode.cache.operation")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .tag("operation", operation)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    public void payload(String cacheName, int bytes) {
        DistributionSummary.builder("cacanode.cache.payload.bytes")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .register(registry)
                .record(bytes);
    }

    public void redisOperation(String component, String operation, String outcome) {
        Counter.builder("cacanode.redis.operations")
                .tag("service", SERVICE)
                .tag("component", controlledComponent(component))
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void authoritativeDuration(String cacheName, String outcome, long elapsedNanos) {
        Timer.builder("cacanode.cache.authoritative")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .tag("outcome", outcome)
                .serviceLevelObjectives(
                        Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
                        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)
                )
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    public void authoritativeLoadStarted(String cacheName, int sameKeyConcurrency) {
        String controlledName = controlledCacheName(cacheName);
        AtomicInteger inFlight = authoritativeLoadsInFlight.computeIfAbsent(controlledName, name -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("cacanode.cache.authoritative.loads.in.flight", value, AtomicInteger::get)
                    .tag("service", SERVICE)
                    .tag("cache", name)
                    .register(registry);
            return value;
        });
        inFlight.incrementAndGet();
        DistributionSummary.builder("cacanode.cache.same.key.concurrency")
                .tag("service", SERVICE)
                .tag("cache", controlledName)
                .serviceLevelObjectives(1, 2, 3, 5, 10, 20, 50, 100)
                .register(registry)
                .record(sameKeyConcurrency);
        if (sameKeyConcurrency > 1) {
            Counter.builder("cacanode.cache.same.key.overlaps")
                    .tag("service", SERVICE)
                    .tag("cache", controlledName)
                    .register(registry)
                    .increment();
        }
    }

    public void authoritativeLoadFinished(String cacheName) {
        AtomicInteger inFlight = authoritativeLoadsInFlight.get(controlledCacheName(cacheName));
        if (inFlight != null) {
            inFlight.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private String controlledCacheName(String cacheName) {
        return CACHE_NAMES.contains(cacheName) ? cacheName : "unknown";
    }

    private String controlledComponent(String component) {
        return Set.of("cache", "public-rate-limit").contains(component) ? component : "unknown";
    }
}
