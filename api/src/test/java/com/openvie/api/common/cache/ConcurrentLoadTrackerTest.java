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
            assertEquals(3.0, registry.get("openvie.cache.authoritative.loads.in.flight")
                    .tags("service", "spring-api", "cache", "workspace")
                    .gauge().value());
            assertEquals(1.0, registry.get("openvie.cache.same.key.overlaps")
                    .tags("service", "spring-api", "cache", "workspace")
                    .counter().count());
            assertEquals(3, registry.get("openvie.cache.same.key.concurrency")
                    .tags("service", "spring-api", "cache", "workspace")
                    .summary().count());
        }

        assertEquals(0, tracker.activeKeyCount());
        assertEquals(0.0, registry.get("openvie.cache.authoritative.loads.in.flight")
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
