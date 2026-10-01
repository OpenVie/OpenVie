package com.cacanode.api.auth.repository;

import com.cacanode.api.auth.api.Login2FAChallengeType;
import com.cacanode.api.auth.model.Login2FAState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@ActiveProfiles("test")
@Sql(statements = """
        drop table if exists login_2fa_state;
        create table login_2fa_state (
            id uuid primary key,
            user_id uuid not null,
            email varchar(255) not null,
            token_hash varchar(255) not null,
            expires_at timestamp not null,
            used boolean not null,
            attempt_count integer not null,
            challenge_type varchar(16) not null,
            verification_attempt_count integer not null,
            created_at timestamp not null,
            updated_at timestamp not null
        );
        """)
class Login2FAStateRepositoryTest {

    @Autowired
    private Login2FAStateRepository repository;

    @Test
    void correctChallengeCanBeConsumedOnlyOnce() {
        Login2FAState state = repository.saveAndFlush(activeCode());
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, repository.consumeIfActive(state.getId(), now));
        assertEquals(0, repository.consumeIfActive(state.getId(), now));
        assertTrue(repository.findById(state.getId()).orElseThrow().isUsed());
    }

    @Test
    void fifthIncorrectAttemptInvalidatesCodeWithoutDeletingIt() {
        Login2FAState state = repository.saveAndFlush(activeCode());

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(1, repository.recordIncorrectAttempt(state.getId(), LocalDateTime.now(), 5));
        }

        Login2FAState invalidated = repository.findById(state.getId()).orElseThrow();
        assertEquals(5, invalidated.getVerificationAttemptCount());
        assertTrue(invalidated.isUsed());
        assertEquals(0, repository.recordIncorrectAttempt(state.getId(), LocalDateTime.now(), 5));
    }

    private Login2FAState activeCode() {
        Login2FAState state = new Login2FAState();
        state.setUserId(UUID.randomUUID());
        state.setEmail("person@example.com");
        state.setTokenHash("$2a$10$bcrypt-only-placeholder");
        state.setChallengeType(Login2FAChallengeType.CODE);
        state.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        state.setAttemptCount(1);
        state.setVerificationAttemptCount(0);
        return state;
    }
}
