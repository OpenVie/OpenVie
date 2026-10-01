package com.cacanode.api.chat.repository;

import com.cacanode.api.chat.enums.ChatChannel;
import com.cacanode.api.chat.enums.ChatSessionStatus;
import com.cacanode.api.chat.model.ChatSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSession session where session.id = :id and session.tenantId = :tenantId")
    Optional<ChatSession> findForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    Optional<ChatSession> findByIdAndTenantIdAndHiddenAtIsNull(UUID id, UUID tenantId);


    List<ChatSession> findByTenantIdAndUserIdAndChannelAndHiddenAtIsNullOrderByLastActivityAtDesc(
            UUID tenantId, UUID userId, ChatChannel channel, Pageable pageable);

    List<ChatSession> findByTenantIdAndChannelInOrderByCreatedAtDesc(
            UUID tenantId, List<ChatChannel> channels, Pageable pageable);

    @Query("""
            select session from ChatSession session
            where session.status = :status
              and session.channel in :channels
              and session.lastActivityAt < :cutoff
            order by session.lastActivityAt asc
            """)
    List<ChatSession> findIdle(
            @Param("status") ChatSessionStatus status,
            @Param("channels") List<ChatChannel> channels,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);
}
