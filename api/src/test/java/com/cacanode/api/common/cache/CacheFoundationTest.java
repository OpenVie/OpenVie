package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheFoundationTest {

    private RedisTemplate<String, byte[]> redisTemplate;
    private ValueOperations<String, byte[]> valueOperations;
    private CacheProperties properties;
    private SimpleMeterRegistry registry;
    private RedisCacheStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        properties = new CacheProperties();
        registry = new SimpleMeterRegistry();
        store = new RedisCacheStore(
                redisTemplate,
                properties,
                new TtlJitter(10, () -> 0.5),
                new CacheMetrics(registry)
        );
    }

    @Test
    void buildsVersionedKeysFromTrustedSegments() {
        CacheKeyFactory factory = new CacheKeyFactory("ccn:v1:");

        assertEquals(
                "ccn:v1:workspace:tenant:00000000-0000-0000-0000-000000000001",
                factory.build("workspace", "tenant", "00000000-0000-0000-0000-000000000001")
        );
    }

    @Test
    void cacheConfigurationDefaultsKeepEveryFutureCacheDisabled() {
        CacheProperties defaults = new CacheProperties();

        assertEquals(false, defaults.isEnabled());
        assertEquals("ccn:v2", defaults.getKeyPrefix());
        assertEquals(10, defaults.getTtlJitterPercent());
        assertEquals(false, defaults.isBusinessReadEnabled());
        assertEquals(false, defaults.isWorkspaceEnabled());
        assertEquals(300, defaults.getWorkspaceTtlSeconds());
        assertEquals(false, defaults.isUserDirectoryEnabled());
        assertEquals(30, defaults.getUserDirectoryTtlSeconds());
        assertEquals(false, defaults.isDocumentListEnabled());
        assertEquals(15, defaults.getDocumentListTtlSeconds());
        assertEquals(false, defaults.isEmbeddingEnabled());
        assertEquals(false, defaults.isRetrievalEnabled());
    }

    @Test
    void appliesDeterministicJitterWithinBoundsAndKeepsOneSecondMinimum() {
        assertEquals(Duration.ofSeconds(9), new TtlJitter(10, () -> 0.0).apply(Duration.ofSeconds(10)));
        assertEquals(Duration.ofSeconds(11), new TtlJitter(10, () -> 1.0).apply(Duration.ofSeconds(10)));
        assertEquals(Duration.ofSeconds(1), new TtlJitter(10, () -> 0.0).apply(Duration.ofMillis(50)));
    }

    @Test
    void disabledStoreBypassesWithoutCallingRedisAndRecordsMetric() {
        CacheReadResult result = store.get("foundation", "ccn:v1:test:key");

        assertEquals(CacheReadStatus.BYPASS, result.status());
        assertNull(result.value());
        assertEquals(1.0, registry.get("cacanode.cache.operations")
                .tags("service", "spring-api", "cache", "foundation", "outcome", "bypass")
                .counter().count());
    }

    @Test
    void returnsHitWithoutExposingMutableRedisBytes() {
        properties.setEnabled(true);
        byte[] stored = new byte[]{0, 1, -1, 42};
        when(valueOperations.get("ccn:v1:test:key")).thenReturn(stored);

        CacheReadResult result = store.get("foundation", "ccn:v1:test:key");
        stored[0] = 99;

        assertEquals(CacheReadStatus.HIT, result.status());
        assertArrayEquals(new byte[]{0, 1, -1, 42}, result.value());
        assertEquals(1.0, registry.get("cacanode.redis.operations")
                .tags("service", "spring-api", "component", "cache", "operation", "get", "outcome", "success")
                .counter().count());
    }

    @Test
    void returnsMissForAbsentValue() {
        properties.setEnabled(true);
        when(valueOperations.get(any())).thenReturn(null);

        assertEquals(CacheReadStatus.MISS, store.get("foundation", "ccn:v1:test:missing").status());
    }

    @Test
    void writesAndDeletesRawBytes() {
        properties.setEnabled(true);
        byte[] payload = new byte[]{0, -128, 127};

        assertEquals(
                CacheOperationStatus.SUCCESS,
                store.put("foundation", "ccn:v1:test:key", payload, Duration.ofSeconds(30))
        );
        assertEquals(
                CacheOperationStatus.SUCCESS,
                store.delete("foundation", "ccn:v1:test:key")
        );

        verify(valueOperations).set(eq("ccn:v1:test:key"), eq(payload), eq(Duration.ofSeconds(30)));
        verify(redisTemplate).delete("ccn:v1:test:key");
        assertEquals(1.0, registry.get("cacanode.cache.operations")
                .tags("service", "spring-api", "cache", "foundation", "outcome", "write")
                .counter().count());
        assertEquals(1.0, registry.get("cacanode.cache.operations")
                .tags("service", "spring-api", "cache", "foundation", "outcome", "invalidate")
                .counter().count());
    }

    @Test
    void redisExceptionsNeverEscapeTheContract() {
        properties.setEnabled(true);
        when(valueOperations.get(any())).thenThrow(new IllegalStateException("unavailable"));
        org.mockito.Mockito.doThrow(new IllegalStateException("unavailable"))
                .when(valueOperations).set(any(), any(), any(Duration.class));
        when(redisTemplate.delete(any(String.class))).thenThrow(new IllegalStateException("unavailable"));

        assertEquals(CacheReadStatus.ERROR, store.get("foundation", "ccn:v1:test:key").status());
        assertEquals(
                CacheOperationStatus.ERROR,
                store.put("foundation", "ccn:v1:test:key", new byte[]{1}, Duration.ofSeconds(5))
        );
        assertEquals(CacheOperationStatus.ERROR, store.delete("foundation", "ccn:v1:test:key"));
        assertEquals(3.0, registry.get("cacanode.cache.operations")
                .tags("service", "spring-api", "cache", "foundation", "outcome", "error")
                .counter().count());
    }

    @Test
    void recordsControlledBusinessAuthoritativeLatency() {
        CacheMetrics metrics = new CacheMetrics(registry);

        metrics.authoritativeDuration("workspace", "success", 1_000_000);

        assertEquals(1, registry.get("cacanode.cache.authoritative")
                .tags("service", "spring-api", "cache", "workspace", "outcome", "success")
                .timer().count());
    }
}
