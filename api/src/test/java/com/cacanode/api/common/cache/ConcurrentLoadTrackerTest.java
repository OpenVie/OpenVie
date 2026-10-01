package com.cacanode.api.common.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ConcurrentLoadTrackerTest {

    @Test
    void detectsSameKeyOverlapAndTracksDifferentKeysIndependently() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConcurrentLoadTracker tracker = new ConcurrentLoadTracker(new CacheMetrics(registry));

        try (ConcurrentLoadTracker.Scope first = tracker.observe("workspace", "tenant-secret");
             ConcurrentLoadTracker.Scope second = tracker.observe("workspace", "tenant-secret");
             ConcurrentLoadTracker.Scope other = tracker.observe("workspace", "other-secret")) {
            assertEquals(2, tracker.activeKeyCount());
            assertEquals(3.0, registry.get("cacanode.cache.authoritative.loads.in.flight")
                    .tags("service", "spring-api", "cache", "workspace")
                    .gauge().value());
            assertEquals(1.0, registry.get("cacanode.cache.same.key.overlaps")
                    .tags("service", "spring-api", "cache", "workspace")
                    .counter().count());
            assertEquals(3, registry.get("cacanode.cache.same.key.concurrency")
                    .tags("service", "spring-api", "cache", "workspace")
                    .summary().count());
        }

        assertEquals(0, tracker.activeKeyCount());
        assertEquals(0.0, registry.get("cacanode.cache.authoritative.loads.in.flight")
                .tags("service", "spring-api", "cache", "workspace")
                .gauge().value());
    }

    @Test
    void storesOnlyHashesAndCleansUpAfterExceptionsAndRepeatedClose() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConcurrentLoadTracker tracker = new ConcurrentLoadTracker(new CacheMetrics(registry));
        String rawKey = "tenant-id:model-id:private-query";

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> {
            try (ConcurrentLoadTracker.Scope ignored = tracker.observe("document-list", rawKey)) {
                Map<String, Integer> active = tracker.activeKeyHashes();
                assertEquals(1, active.size());
                assertFalse(active.keySet().iterator().next().contains(rawKey));
                throw new IllegalStateException("authoritative failure");
            }
        });

        assertEquals("authoritative failure", error.getMessage());
        assertTrue(tracker.activeKeyHashes().isEmpty());
        ConcurrentLoadTracker.Scope scope = tracker.observe("document-list", rawKey);
        scope.close();
        scope.close();
        assertEquals(0, tracker.activeKeyCount());
    }

    @Test
    void metricFailureNeverChangesExecutionOrLeaksTrackerState() {
        CacheMetrics metrics = mock(CacheMetrics.class);
        doThrow(new IllegalStateException("registry unavailable"))
                .when(metrics).authoritativeLoadStarted("document-list", 1);
        ConcurrentLoadTracker tracker = new ConcurrentLoadTracker(metrics);

        try (ConcurrentLoadTracker.Scope ignored = tracker.observe("document-list", "private-key")) {
            assertEquals(1, tracker.activeKeyCount());
        }

        assertEquals(0, tracker.activeKeyCount());
    }
}
