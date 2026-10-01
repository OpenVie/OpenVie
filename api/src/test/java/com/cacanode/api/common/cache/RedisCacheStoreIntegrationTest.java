package com.cacanode.api.common.cache;

import com.cacanode.api.common.config.CacheProperties;
import com.cacanode.api.common.config.RedisConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCacheStoreIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_TEST_URL", matches = ".+")
    void exercisesCacheContractAgainstRedisDatabase15() {
        URI uri = URI.create(System.getenv("REDIS_TEST_URL"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                uri.getHost(), uri.getPort() == -1 ? 6379 : uri.getPort()
        );
        configuration.setDatabase(15);
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            configuration.setPassword(uri.getUserInfo().substring(uri.getUserInfo().indexOf(':') + 1));
        }
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        RedisTemplate<String, byte[]> template = new RedisConfig().byteRedisTemplate(connectionFactory);
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        RedisCacheStore store = new RedisCacheStore(
                template,
                properties,
                new TtlJitter(0, () -> 0.5),
                new CacheMetrics(new SimpleMeterRegistry())
        );
        String key = "ccn:test:" + UUID.randomUUID() + ":raw";
        try {
            assertEquals(CacheReadStatus.MISS, store.get("foundation", key).status());
            assertEquals(
                    CacheOperationStatus.SUCCESS,
                    store.put("foundation", key, new byte[]{0, -1, 42}, Duration.ofSeconds(30))
            );
            assertArrayEquals(new byte[]{0, -1, 42}, store.get("foundation", key).value());
            assertTrue(template.getExpire(key) > 0);
            assertEquals(CacheOperationStatus.SUCCESS, store.delete("foundation", key));
            assertEquals(CacheReadStatus.MISS, store.get("foundation", key).status());
        } finally {
            try {
                template.delete(key);
            } catch (RuntimeException ignored) {
                // Preserve the original assertion when Redis is unreachable during cleanup.
            }
            connectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_TEST_URL", matches = ".+")
    void exercisesDocumentGenerationAgainstRedisDatabase15() {
        URI uri = URI.create(System.getenv("REDIS_TEST_URL"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                uri.getHost(), uri.getPort() == -1 ? 6379 : uri.getPort()
        );
        configuration.setDatabase(15);
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            configuration.setPassword(uri.getUserInfo().substring(uri.getUserInfo().indexOf(':') + 1));
        }
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        RedisTemplate<String, byte[]> template = new RedisConfig().byteRedisTemplate(connectionFactory);
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        properties.setBusinessReadEnabled(true);
        properties.setDocumentListEnabled(true);
        String prefix = "ccn:test:" + UUID.randomUUID();
        CacheKeyFactory keys = new CacheKeyFactory(prefix);
        RedisDocumentListGenerationStore generations = new RedisDocumentListGenerationStore(
                template, keys, properties, new CacheMetrics(new SimpleMeterRegistry()));
        UUID tenantId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        String generationKey = keys.build("documents-generation", "tenant", tenantId.toString(),
                "kb", knowledgeBaseId.toString());
        try {
            assertEquals(OptionalLong.of(0), generations.current(tenantId, knowledgeBaseId));
            assertEquals(CacheOperationStatus.SUCCESS, generations.increment(tenantId, knowledgeBaseId));
            assertEquals(OptionalLong.of(1), generations.current(tenantId, knowledgeBaseId));
            template.opsForValue().set(generationKey, "corrupt".getBytes());
            assertTrue(generations.current(tenantId, knowledgeBaseId).isEmpty());
        } finally {
            try {
                template.delete(generationKey);
            } catch (RuntimeException ignored) {
                // Preserve the original assertion when Redis is unreachable during cleanup.
            }
            connectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_TEST_URL", matches = ".+")
    void unreachableRedisFailsOpenForEveryCacheOperation() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", 1);
        configuration.setDatabase(15);
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(100))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration, clientConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        RedisTemplate<String, byte[]> template = new RedisConfig().byteRedisTemplate(connectionFactory);
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        RedisCacheStore store = new RedisCacheStore(
                template, properties, new TtlJitter(0, () -> 0.5),
                new CacheMetrics(new SimpleMeterRegistry()));
        try {
            assertEquals(CacheReadStatus.ERROR, store.get("foundation", "ccn:test:unreachable").status());
            assertEquals(CacheOperationStatus.ERROR, store.put(
                    "foundation", "ccn:test:unreachable", new byte[]{1}, Duration.ofSeconds(5)));
            assertEquals(CacheOperationStatus.ERROR, store.delete("foundation", "ccn:test:unreachable"));
        } finally {
            connectionFactory.destroy();
        }
    }
}
