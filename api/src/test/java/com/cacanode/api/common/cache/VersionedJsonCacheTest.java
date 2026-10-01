package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VersionedJsonCacheTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CacheProperties properties;
    private FakeStore store;
    private CacheMetrics metrics;
    private VersionedJsonCache cache;

    @BeforeEach
    void setUp() {
        properties = new CacheProperties();
        properties.setEnabled(true);
        properties.setBusinessReadEnabled(true);
        properties.setUserDirectoryEnabled(true);
        store = new FakeStore();
        metrics = mock(CacheMetrics.class);
        cache = new VersionedJsonCache(store, properties, metrics, mapper);
    }

    @AfterEach
    void cleanSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void missFillsVersionedJsonAndSecondReadHitsWithoutAuthoritativeLoad() throws Exception {
        AtomicInteger loads = new AtomicInteger();

        Sample first = cache.getOrLoad(BusinessCache.USER_DIRECTORY, "ccn:v1:user-directory:tenant:t1",
                Sample.class, () -> new Sample("value-" + loads.incrementAndGet()));
        Sample second = cache.getOrLoad(BusinessCache.USER_DIRECTORY, "ccn:v1:user-directory:tenant:t1",
                Sample.class, () -> new Sample("value-" + loads.incrementAndGet()));

        assertEquals(new Sample("value-1"), first);
        assertEquals(first, second);
        assertEquals(1, loads.get());
        var json = mapper.readTree(store.values.get("ccn:v1:user-directory:tenant:t1"));
        assertEquals(1, json.get("schema_version").asInt());
        assertEquals("value-1", json.get("payload").get("value").asText());
        assertEquals(Duration.ofSeconds(30), store.lastTtl);
        verify(metrics).authoritativeDuration(eq("user-directory"), eq("success"), anyLong());
    }

    @Test
    void disabledDomainBypassesStoreAndStillMeasuresAuthoritativeLoad() {
        properties.setUserDirectoryEnabled(false);

        assertEquals(new Sample("database"), cache.getOrLoad(BusinessCache.USER_DIRECTORY, "key",
                Sample.class, () -> new Sample("database")));

        assertEquals(0, store.gets);
        assertTrue(store.values.isEmpty());
        verify(metrics).cacheOperation("user-directory", "bypass");
    }

    @Test
    void corruptAndUnknownSchemasAreDeletedAndReplaced() {
        store.values.put("corrupt", "not-json".getBytes());
        store.values.put("unknown", "{\"schema_version\":2,\"payload\":{\"value\":\"old\"}}".getBytes());

        assertEquals(new Sample("fresh"), cache.getOrLoad(BusinessCache.USER_DIRECTORY, "corrupt",
                Sample.class, () -> new Sample("fresh")));
        assertEquals(new Sample("fresh"), cache.getOrLoad(BusinessCache.USER_DIRECTORY, "unknown",
                Sample.class, () -> new Sample("fresh")));

        assertEquals(2, store.deletes);
        assertTrue(new String(store.values.get("corrupt")).contains("\"schema_version\":1"));
        assertTrue(new String(store.values.get("unknown")).contains("\"schema_version\":1"));
    }

    @Test
    void redisReadErrorFallsBackAndWriteErrorDoesNotChangeResponse() {
        store.readStatus = CacheReadStatus.ERROR;
        store.writeStatus = CacheOperationStatus.ERROR;

        assertEquals(new Sample("database"), cache.getOrLoad(BusinessCache.USER_DIRECTORY, "key",
                Sample.class, () -> new Sample("database")));
    }

    @Test
    void fillRunsOnlyAfterCommitAndRollbackSuppressesIt() {
        TransactionSynchronizationManager.initSynchronization();
        cache.getOrLoad(BusinessCache.USER_DIRECTORY, "commit", Sample.class, () -> new Sample("ok"));
        assertFalse(store.values.containsKey("commit"));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertTrue(store.values.containsKey("commit"));
        TransactionSynchronizationManager.clearSynchronization();

        TransactionSynchronizationManager.initSynchronization();
        cache.getOrLoad(BusinessCache.USER_DIRECTORY, "rollback", Sample.class, () -> new Sample("no"));
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertFalse(store.values.containsKey("rollback"));
    }

    @Test
    void exceptionsAndNotFoundValuesAreNeverCached() {
        assertNull(cache.getOrLoad(BusinessCache.USER_DIRECTORY, "missing", Sample.class, () -> null));
        assertThrows(IllegalStateException.class, () -> cache.getOrLoad(
                BusinessCache.USER_DIRECTORY, "error", Sample.class,
                () -> { throw new IllegalStateException("database unavailable"); }));
        assertTrue(store.values.isEmpty());
        verify(metrics).authoritativeDuration(eq("user-directory"), eq("not_found"), anyLong());
        verify(metrics).authoritativeDuration(eq("user-directory"), eq("error"), anyLong());
    }

    record Sample(String value) {}

    private static final class FakeStore implements CacheStore {
        private final Map<String, byte[]> values = new HashMap<>();
        private CacheReadStatus readStatus;
        private CacheOperationStatus writeStatus = CacheOperationStatus.SUCCESS;
        private int gets;
        private int deletes;
        private Duration lastTtl;

        @Override
        public CacheReadResult get(String cacheName, String fullKey) {
            gets++;
            if (readStatus != null) return CacheReadResult.of(readStatus);
            byte[] value = values.get(fullKey);
            return value == null ? CacheReadResult.of(CacheReadStatus.MISS) : CacheReadResult.hit(value);
        }

        @Override
        public CacheOperationStatus put(String cacheName, String fullKey, byte[] value, Duration baseTtl) {
            if (writeStatus == CacheOperationStatus.SUCCESS) values.put(fullKey, value.clone());
            lastTtl = baseTtl;
            return writeStatus;
        }

        @Override
        public CacheOperationStatus delete(String cacheName, String fullKey) {
            deletes++;
            values.remove(fullKey);
            return CacheOperationStatus.SUCCESS;
        }
    }
}
