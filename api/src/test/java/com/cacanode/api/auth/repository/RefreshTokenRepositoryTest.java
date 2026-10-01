package com.cacanode.api.auth.repository;

import com.cacanode.api.auth.model.RefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@ActiveProfiles("test")
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Sql(statements = """
            create table refresh_tokens (
                id uuid primary key,
                user_id uuid not null,
                tenant_id uuid not null,
                token_hash varchar(255) unique not null,
                expires_at timestamp not null,
                revoked boolean not null,
                persistent boolean not null,
                created_at timestamp not null
            );
            """)
    void simultaneousConditionalConsumptionAllowsExactlyOneWinner() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        RefreshToken stored = transactions.execute(status -> repository.saveAndFlush(token()));
        String tokenHash = stored.getTokenHash();
        UUID tokenId = stored.getId();

        CountDownLatch bothLoaded = new CountDownLatch(2);
        CountDownLatch startConsume = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> transactions.execute(status -> {
                assertTrue(repository.findByTokenHash(tokenHash).isPresent());
                bothLoaded.countDown();
                await(startConsume);
                return repository.consumeActiveToken(tokenId, tokenHash, LocalDateTime.now());
            }));
            Future<Integer> second = executor.submit(() -> transactions.execute(status -> {
                assertTrue(repository.findByTokenHash(tokenHash).isPresent());
                bothLoaded.countDown();
                await(startConsume);
                return repository.consumeActiveToken(tokenId, tokenHash, LocalDateTime.now());
            }));

            assertTrue(bothLoaded.await(5, TimeUnit.SECONDS));
            startConsume.countDown();

            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertFalse(repository.findByTokenHash(tokenHash).isPresent());
        } finally {
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to consume refresh token");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while consuming refresh token", exception);
        }
    }

    private RefreshToken token() {
        RefreshToken token = new RefreshToken();
        token.setUserId(UUID.randomUUID());
        token.setTenantId(UUID.randomUUID());
        token.setTokenHash("hash-" + UUID.randomUUID());
        token.setExpiresAt(LocalDateTime.now().plusDays(1));
        token.setPersistent(true);
        return token;
    }
}
