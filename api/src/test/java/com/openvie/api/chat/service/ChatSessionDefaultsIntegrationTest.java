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

package com.openvie.api.chat.service;

import com.openvie.api.ai.model.ModelConfigVersion;
import com.openvie.api.chat.dto.ChatDtos;
import com.openvie.api.chat.query.ChatControlPlaneService;
import com.openvie.api.common.exception.custom.ResourceNotFoundException;
import com.openvie.api.testsupport.PostgresTestContainer;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.enums.ChatbotStatus;
import com.openvie.api.tenant.enums.KnowledgeBaseStatus;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Chatbot;
import com.openvie.api.tenant.model.KnowledgeBase;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.repository.ChatbotRepository;
import com.openvie.api.tenant.repository.KnowledgeBaseRepository;
import com.openvie.api.tenant.repository.UserRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves session creation treats `chatbot_id`/`knowledge_base_id` as optional:
 * an empty request body binds the session to the workspace's default assistant
 * (what `GET /tenants/me/workspace` exposes), so an API-key integrator can chat
 * without discovering internal uuids. Explicit ids stay validated.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
class ChatSessionDefaultsIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_chat_defaults");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private ChatControlPlaneService chatService;

    @Autowired
    private ChatbotRepository chatbotRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    private UUID workspaceId;
    private UUID coordinatorId;

    @BeforeEach
    void seed() {
        transactions.executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Organization organization = new Organization();
            organization.setName("Chat Defaults Org " + suffix);
            organization.setSlug("chat-defaults-" + suffix);
            em.persist(organization);
            em.flush();

            Tenant workspace = new Tenant();
            workspace.setOrganization(organization);
            workspace.setName("Chat Defaults Workspace");
            workspace.setSlug("chat-defaults-ws-" + suffix);
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            workspaceId = workspace.getId();

            User coordinator = new User();
            coordinator.setOrganization(organization);
            coordinator.setEmail("coordinator-" + suffix + "@defaults.example");
            coordinator.setPasswordHash("{noop}not-used");
            coordinator.setFullName("Coordinator");
            coordinator.setRole(OrgRole.ORG_OWNER);
            coordinator.setStatus(UserStatus.ACTIVE);
            em.persist(coordinator);
            em.flush();
            coordinatorId = coordinator.getId();

            WorkspaceMember membership = new WorkspaceMember();
            membership.setUser(coordinator);
            membership.setWorkspace(workspace);
            membership.setRole(WorkspaceRole.WORKSPACE_ADMIN);
            em.persist(membership);

            KnowledgeBase olderKb = new KnowledgeBase();
            olderKb.setTenant(workspace);
            olderKb.setName("Old KB");
            olderKb.setSlug("old-kb-" + suffix);
            olderKb.setStatus(KnowledgeBaseStatus.ACTIVE);
            em.persist(olderKb);
            em.flush();

            ModelConfigVersion modelConfig = new ModelConfigVersion();
            modelConfig.setName("defaults-test-model-" + suffix);
            modelConfig.setVersionLabel("v1-test");
            modelConfig.setGenerationModelId("test-model");
            modelConfig.setTextEmbeddingModelId("test-embed");
            modelConfig.setTextEmbeddingDimension(8);
            em.persist(modelConfig);
            em.flush();

            Chatbot olderAssistant = new Chatbot();
            olderAssistant.setTenant(workspace);
            olderAssistant.setKnowledgeBase(olderKb);
            olderAssistant.setModelConfigVersionId(modelConfig.getId());
            olderAssistant.setDisplayName("Oldest Assistant");
            olderAssistant.setDefaultLocale("vi-VN");
            olderAssistant.setWelcomeMessage("hi");
            olderAssistant.setSafeInstructions("answer from documents");
            olderAssistant.setRetrievalSettings(Map.of("topK", 4));
            olderAssistant.setStatus(ChatbotStatus.ACTIVE);
            em.persist(olderAssistant);
            em.flush();
        });
    }

    @Test
    void emptyBodyBindsSessionToTheWorkspacesDefaultAssistant() {
        Chatbot expected = chatbotRepository
                .findFirstByTenant_IdAndStatusOrderByCreatedAtAsc(workspaceId, ChatbotStatus.ACTIVE)
                .orElseThrow();

        var created = transactions.execute(status -> chatService.createEmployeeSession(
                workspaceId, coordinatorId,
                new ChatDtos.CreateSessionRequest(null, null, "vi", Map.of())));

        assertNotNull(created.id());
        assertEquals(expected.getId().toString(), created.chatbotId(),
                "the default (oldest) assistant must be applied when no ids are sent");
        assertEquals(expected.getKnowledgeBase().getId().toString(), created.knowledgeBaseId(),
                "the default assistant's knowledge base must be applied");
        assertTrue(created.tenantId().toString().equals(workspaceId.toString()));
    }

    @Test
    void explicitPairStillValidatesAgainstTheWorkspace() {
        Chatbot assistant = chatbotRepository
                .findFirstByTenant_IdAndStatusOrderByCreatedAtAsc(workspaceId, ChatbotStatus.ACTIVE)
                .orElseThrow();

        // Exact pair: accepted and mirrored in the response.
        var created = transactions.execute(status -> chatService.createEmployeeSession(
                workspaceId, coordinatorId,
                new ChatDtos.CreateSessionRequest(assistant.getId(),
                        assistant.getKnowledgeBase().getId(), "vi", Map.of())));
        assertEquals(assistant.getId().toString(), created.chatbotId());

        // Wrong pair (kb from another workspace / unknown chatbot): NOT_FOUND.
        assertThrows(ResourceNotFoundException.class, () -> transactions.execute(status ->
                chatService.createEmployeeSession(workspaceId, coordinatorId,
                        new ChatDtos.CreateSessionRequest(UUID.randomUUID(),
                                assistant.getKnowledgeBase().getId(), "vi", Map.of()))));
    }

    @Test
    void knowledgeBaseAlonePicksItsBoundAssistant() {
        Chatbot assistant = chatbotRepository
                .findFirstByTenant_IdAndStatusOrderByCreatedAtAsc(workspaceId, ChatbotStatus.ACTIVE)
                .orElseThrow();

        var created = transactions.execute(status -> chatService.createEmployeeSession(
                workspaceId, coordinatorId,
                new ChatDtos.CreateSessionRequest(null, assistant.getKnowledgeBase().getId(), "vi", Map.of())));

        assertEquals(assistant.getId().toString(), created.chatbotId(),
                "knowledge-base-only requests resolve the chatbot bound to that KB");
    }
}
