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
        Counter.builder("openvie.cache.operations")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void cacheDuration(String cacheName, String operation, long elapsedNanos) {
        Timer.builder("openvie.cache.operation")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .tag("operation", operation)
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    public void payload(String cacheName, int bytes) {
        DistributionSummary.builder("openvie.cache.payload.bytes")
                .tag("service", SERVICE)
                .tag("cache", controlledCacheName(cacheName))
                .register(registry)
                .record(bytes);
    }

    public void redisOperation(String component, String operation, String outcome) {
        Counter.builder("openvie.redis.operations")
                .tag("service", SERVICE)
                .tag("component", controlledComponent(component))
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void authoritativeDuration(String cacheName, String outcome, long elapsedNanos) {
        Timer.builder("openvie.cache.authoritative")
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
            Gauge.builder("openvie.cache.authoritative.loads.in.flight", value, AtomicInteger::get)
                    .tag("service", SERVICE)
                    .tag("cache", name)
                    .register(registry);
            return value;
        });
        inFlight.incrementAndGet();
        DistributionSummary.builder("openvie.cache.same.key.concurrency")
                .tag("service", SERVICE)
                .tag("cache", controlledName)
                .serviceLevelObjectives(1, 2, 3, 5, 10, 20, 50, 100)
                .register(registry)
                .record(sameKeyConcurrency);
        if (sameKeyConcurrency > 1) {
            Counter.builder("openvie.cache.same.key.overlaps")
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
