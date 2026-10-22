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

package com.openvie.api.chat.repository;

import com.openvie.api.chat.enums.ChatChannel;
import com.openvie.api.chat.enums.ChatSessionStatus;
import com.openvie.api.chat.model.ChatSession;
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
