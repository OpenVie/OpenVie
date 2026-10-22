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

package com.openvie.api.tenant.repository;

import com.openvie.api.tenant.model.Chatbot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.openvie.api.tenant.enums.ChatbotStatus;

public interface ChatbotRepository extends JpaRepository<Chatbot, UUID> {
    List<Chatbot> findByTenantId(UUID tenantId);

    Optional<Chatbot> findFirstByTenant_IdAndKnowledgeBase_IdAndStatusOrderByCreatedAtAsc(
            UUID tenantId,
            UUID knowledgeBaseId,
            com.openvie.api.tenant.enums.ChatbotStatus status
    );

    Optional<Chatbot> findByIdAndTenant_IdAndKnowledgeBase_IdAndStatus(
            UUID id, UUID tenantId, UUID knowledgeBaseId, ChatbotStatus status);
}
