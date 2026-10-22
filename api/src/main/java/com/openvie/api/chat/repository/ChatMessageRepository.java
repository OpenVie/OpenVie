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

import com.openvie.api.chat.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID sessionId, int after, Pageable pageable);

    List<ChatMessage> findBySessionIdOrderBySequenceNumberAsc(UUID sessionId);

    List<ChatMessage> findBySessionIdOrderBySequenceNumberDesc(UUID sessionId, Pageable pageable);

    List<ChatMessage> findBySessionIdAndSequenceNumberLessThanOrderBySequenceNumberAsc(
            UUID sessionId, int sequenceNumber, Pageable pageable);

    Optional<ChatMessage> findFirstBySessionIdAndRoleOrderBySequenceNumberAsc(UUID sessionId, String role);

    long countBySessionId(UUID sessionId);

    Optional<ChatMessage> findFirstBySessionIdAndSequenceNumberLessThanOrderBySequenceNumberDesc(
            UUID sessionId, int sequenceNumber);
}
