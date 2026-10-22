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

package com.openvie.api.tenant.service.implement;

import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.tenant.api.TenantWorkspaceApi;
import com.openvie.api.tenant.enums.ChatbotStatus;
import com.openvie.api.tenant.enums.KnowledgeBaseStatus;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.repository.ChatbotRepository;
import com.openvie.api.tenant.repository.KnowledgeBaseRepository;
import com.openvie.api.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantWorkspaceApiImpl implements TenantWorkspaceApi {
    private final TenantRepository tenantRepository;
    private final ChatbotRepository chatbotRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.openvie.api.tenant.service.KnowledgeBaseRevisionService revisionService;

    @Override
    @Transactional(readOnly = true)
    public WorkspaceContext requireActiveWorkspace(UUID tenantId, UUID chatbotId, UUID knowledgeBaseId) {
        var tenant = activeTenant(tenantId);
        var chatbot = chatbotRepository.findByIdAndTenant_IdAndKnowledgeBase_IdAndStatus(
                        chatbotId, tenantId, knowledgeBaseId, ChatbotStatus.ACTIVE)
                .orElseThrow(this::notFound);
        var knowledgeBase = chatbot.getKnowledgeBase();
        if (knowledgeBase.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw notFound();
        }
        return new WorkspaceContext(tenantId, chatbotId, knowledgeBaseId, tenant.getName(),
                knowledgeBase.getSearchRevision());
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceContext requireActiveKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        var tenant = activeTenant(tenantId);
        var knowledgeBase = knowledgeBaseRepository.findByIdAndTenantId(knowledgeBaseId, tenantId)
                .filter(item -> item.getStatus() == KnowledgeBaseStatus.ACTIVE)
                .orElseThrow(this::notFound);
        return new WorkspaceContext(tenantId, null, knowledgeBaseId, tenant.getName(),
                knowledgeBase.getSearchRevision());
    }

    @Override
    public void incrementSearchRevision(UUID tenantId, UUID knowledgeBaseId) {
        revisionService.increment(tenantId, knowledgeBaseId);
    }

    private com.openvie.api.tenant.model.Tenant activeTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
                .orElseThrow(this::notFound);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Tenant workspace was not found");
    }
}
