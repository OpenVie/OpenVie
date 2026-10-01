package com.cacanode.api.auth.repository;

import com.cacanode.api.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.userId = :userId and r.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken r where r.id = :id and r.tokenHash = :tokenHash "
            + "and r.revoked = false and r.expiresAt > :now")
    int consumeActiveToken(
            @Param("id") UUID id,
            @Param("tokenHash") String tokenHash,
            @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revoked = true "
            + "where r.tokenHash = :tokenHash and r.revoked = false")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash);

}
