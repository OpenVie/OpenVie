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

package com.openvie.api.document.repository;

import com.openvie.api.document.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.openvie.api.document.enums.DocumentStatus;
import org.springframework.data.domain.Pageable;

public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {
    Optional<Document> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Document> findFirstByTenantIdAndKnowledgeBaseIdAndFileNameOrderByCreatedAtDesc(
            UUID tenantId, UUID knowledgeBaseId, String fileName);


    List<Document> findByTenantIdAndKnowledgeBaseIdOrderByCreatedAtDescIdDesc(
            UUID tenantId,
            UUID knowledgeBaseId
    );

    List<Document> findByTenantIdAndKnowledgeBaseIdAndStatus(
            UUID tenantId, UUID knowledgeBaseId, DocumentStatus status);


    List<Document> findByIdInAndTenantIdAndKnowledgeBaseIdAndStatus(
            List<UUID> ids, UUID tenantId, UUID knowledgeBaseId, DocumentStatus status);

    List<Document> findByStatusOrderByIdAsc(DocumentStatus status, Pageable pageable);

    List<Document> findByStatusAndIdGreaterThanOrderByIdAsc(
            DocumentStatus status, UUID afterId, Pageable pageable);
}
