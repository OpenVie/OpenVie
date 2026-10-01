package com.cacanode.api.auth.repository;

import com.cacanode.api.auth.model.Login2FAState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface Login2FAStateRepository extends JpaRepository<Login2FAState, UUID> {
    Optional<Login2FAState> findByTokenHash(String tokenHash);

    Optional<Login2FAState> findByUserId(UUID userId);

    Optional<Login2FAState> findByEmail(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Login2FAState state
               set state.used = true
             where state.id = :id
               and state.used = false
               and state.expiresAt > :now
            """)
    int consumeIfActive(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Login2FAState state
               set state.verificationAttemptCount = state.verificationAttemptCount + 1,
                   state.used = case
                       when state.verificationAttemptCount + 1 >= :maximumAttempts then true
                       else state.used
                   end
             where state.id = :id
               and state.used = false
               and state.expiresAt > :now
            """)
    int recordIncorrectAttempt(
            @Param("id") UUID id,
            @Param("now") LocalDateTime now,
            @Param("maximumAttempts") int maximumAttempts);
}
