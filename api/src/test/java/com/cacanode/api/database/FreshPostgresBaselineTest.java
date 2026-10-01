package com.cacanode.api.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.cacanode.api.chat.enums.ChatChannel;
import com.cacanode.api.chat.enums.ChatSessionStatus;
import com.cacanode.api.chat.exception.ChatApiException;
import com.cacanode.api.chat.model.ChatSession;
import com.cacanode.api.chat.query.ChatControlPlaneService;
import com.cacanode.api.chat.repository.ChatSessionRepository;
import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.document.api.DocumentApi;
import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import com.cacanode.api.document.model.Document;
import com.cacanode.api.tenant.enums.ChatbotStatus;
import com.cacanode.api.tenant.enums.KnowledgeBaseStatus;
import com.cacanode.api.tenant.enums.UserRole;
import com.cacanode.api.tenant.enums.UserStatus;
import com.cacanode.api.tenant.model.Chatbot;
import com.cacanode.api.tenant.model.KnowledgeBase;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.ai.model.ModelConfigVersion;
import com.cacanode.api.testsupport.PostgresTestContainer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Proves the new-public-install database contract against a real, empty
 * PostgreSQL database: the reviewed Flyway baseline migrates from scratch,
 * Hibernate validates the entities against it, and no removed control-plane
 * surface (public registration, widget/external/platform routes) exists.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        // The test profile pins the H2 driver for the in-memory suite; this test
        // replaces the URL with a real PostgreSQL container, so the driver must
        // follow it.
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FreshPostgresBaselineTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_baseline");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private DocumentApi documentApi;

    @Autowired
    private ChatControlPlaneService chatControlPlaneService;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private TransactionTemplate transactions;

    @PersistenceContext
    private EntityManager em;

    @Test
    void flywayMigratesTheBaselineIntoAnEmptyDatabase() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank");
        assertEquals(2, history.size(), "baseline must contain exactly the schema and model seed");
        assertEquals("1", String.valueOf(history.get(0).get("version")));
        assertEquals("2", String.valueOf(history.get(1).get("version")));
        history.forEach(row -> assertTrue((Boolean) row.get("success"),
                "migration " + row.get("version") + " must be recorded as successful"));
    }

    @Test
    void schemaContainsExactlyTheRetainedTables() {
        Set<String> expected = Set.of(
                "tenants", "users", "invitations", "knowledge_bases", "chatbots",
                "model_config_versions",
                "refresh_tokens", "login_2fa_state",
                "chat_sessions", "chat_messages", "chat_turns",
                "documents", "internal_event_outbox", "internal_event_inbox",
                "module_event_outbox", "module_event_inbox",
                "audit_logs", "notifications");
        List<String> actual = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_name <> 'flyway_schema_history'",
                String.class);
        assertEquals(expected, new HashSet<>(actual),
                "fresh install must contain exactly the retained first-commit tables");
    }

    @Test
    void defaultModelConfigurationIsLocalAndActive() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select name, generation_runtime, generation_endpoint, status from model_config_versions");
        assertEquals(1, rows.size(), "the baseline seeds exactly one active model configuration");
        Map<String, Object> row = rows.get(0);
        assertEquals("ACTIVE", row.get("status"));
        assertEquals("ollama", row.get("generation_runtime"));
        String endpoint = String.valueOf(row.get("generation_endpoint"));
        assertFalse(endpoint.toLowerCase().contains("openai"),
                "the seeded default must not point at a hosted provider: " + endpoint);
    }

    @Test
    void publicRegistrationAndRemovedSurfacesHaveNoRoute() {
        Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    Set<String> values = new HashSet<String>();
                    if (info.getPathPatternsCondition() != null) {
                        values.addAll(info.getPathPatternsCondition().getPatternValues());
                    }
                    if (info.getPatternsCondition() != null) {
                        values.addAll(info.getPatternsCondition().getPatterns());
                    }
                    return values.stream();
                }).collect(java.util.stream.Collectors.toSet());

        assertTrue(patterns.contains("/api/v1/auth/login"), "retained login route must exist");
        assertFalse(patterns.contains("/api/v1/auth/register"),
                "public tenant registration must not exist at the API");
        for (String removed : List.of(
                "/api/v1/widget/chat", "/api/v1/external/tickets", "/api/v1/external/chat",
                "/api/v1/public/billing/payos/webhook", "/api/v1/platform", "/api/auth/register")) {
            assertFalse(patterns.stream().anyMatch(pattern -> pattern.startsWith(removed)),
                    "removed surface must not be reachable: " + removed);
        }
        assertFalse(patterns.stream().anyMatch(pattern -> pattern.contains("/register")),
                "no registration handler may remain");
    }

    @Test
    void unauthenticatedRequestsAreDeniedForDocumentsAndChat() throws Exception {
        assertDenied(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")));
        assertDenied(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/chat/sessions")
                .contentType("application/json").content("{}")));
    }

    @Test
    void citationValidationRejectsForgedForeignAndIncompleteDocuments() {
        UUID[] tenantIds = new UUID[2];
        KnowledgeBase[] knowledgeBases = new KnowledgeBase[2];
        User[] users = new User[2];
        Document[] documents = new Document[1];
        Document[] pending = new Document[1];

        transactions.executeWithoutResult(status -> {
            Tenant tenantA = newTenant("Baseline A");
            Tenant tenantB = newTenant("Baseline B");
            knowledgeBases[0] = newKnowledgeBase(tenantA, "kb-a");
            knowledgeBases[1] = newKnowledgeBase(tenantB, "kb-b");
            users[0] = newUser(tenantA, "citation-a@baseline.test");
            users[1] = newUser(tenantB, "citation-b@baseline.test");
            em.persist(tenantA);
            em.persist(tenantB);
            em.flush();
            tenantIds[0] = tenantA.getId();
            tenantIds[1] = tenantB.getId();
            em.persist(knowledgeBases[0]);
            em.persist(knowledgeBases[1]);
            em.persist(users[0]);
            em.persist(users[1]);
            documents[0] = newDocument(tenantA, users[0], knowledgeBases[0], DocumentStatus.COMPLETED);
            pending[0] = newDocument(tenantA, users[0], knowledgeBases[0], DocumentStatus.PENDING);
            em.persist(documents[0]);
            em.persist(pending[0]);
            em.flush();
        });
        em.clear();

        UUID completedDocumentId = documents[0].getId();
        assertDoesNotThrow(() -> documentApi.validateCitations(
                tenantIds[0], knowledgeBases[0].getId(), List.of(completedDocumentId)),
                "a completed document of the same tenant and knowledge base is a valid citation");

        assertThrows(BadRequestException.class, () -> documentApi.validateCitations(
                        tenantIds[1], knowledgeBases[1].getId(), List.of(completedDocumentId)),
                "tenant B may not cite tenant A's document");
        assertThrows(BadRequestException.class, () -> documentApi.validateCitations(
                        tenantIds[0], knowledgeBases[0].getId(), List.of(UUID.randomUUID())),
                "forged citation identifiers must be rejected");
        assertThrows(BadRequestException.class, () -> documentApi.validateCitations(
                        tenantIds[0], knowledgeBases[0].getId(), List.of(pending[0].getId())),
                "only completed documents may back a citation");
    }

    @Test
    void chatSessionAccessIsScopedToTenantAndOwner() {
        UUID[] ids = new UUID[4];
        transactions.executeWithoutResult(status -> {
            Tenant tenantA = newTenant("Session A");
            Tenant tenantB = newTenant("Session B");
            em.persist(tenantA);
            em.persist(tenantB);
            em.flush();
            User owner = newUser(tenantA, "session-owner@baseline.test");
            User stranger = newUser(tenantA, "session-stranger@baseline.test");
            em.persist(owner);
            em.persist(stranger);
            KnowledgeBase kb = newKnowledgeBase(tenantA, "kb-session");
            em.persist(kb);
            ModelConfigVersion model = em.createQuery(
                            "select m from ModelConfigVersion m where m.status = :status",
                            ModelConfigVersion.class)
                    .setParameter("status", com.cacanode.api.ai.enums.ModelConfigStatus.ACTIVE)
                    .getSingleResult();
            Chatbot chatbot = new Chatbot();
            chatbot.setTenant(tenantA);
            chatbot.setKnowledgeBase(kb);
            chatbot.setModelConfigVersionId(model.getId());
            chatbot.setDisplayName("Baseline Assistant");
            chatbot.setWelcomeMessage("hello");
            chatbot.setSafeInstructions("internal");
            chatbot.setStatus(ChatbotStatus.ACTIVE);
            em.persist(chatbot);
            em.flush();

            ChatSession session = new ChatSession();
            session.setTenantId(tenantA.getId());
            session.setUserId(owner.getId());
            session.setChatbotId(chatbot.getId());
            session.setKnowledgeBaseId(kb.getId());
            session.setStatus(ChatSessionStatus.OPEN);
            session.setChannel(ChatChannel.EMPLOYEE_PLAYGROUND);
            em.persist(session);
            em.flush();

            ids[0] = tenantA.getId();
            ids[1] = tenantB.getId();
            ids[2] = owner.getId();
            ids[3] = session.getId();
        });
        em.clear();

        // findForUpdate takes a pessimistic lock, so the assertions run inside a
        // transaction like the setup above.
        transactions.executeWithoutResult(status -> {
            assertTrue(sessionRepository.findForUpdate(ids[3], ids[0]).isPresent());
            assertTrue(sessionRepository.findForUpdate(ids[3], ids[1]).isEmpty(),
                    "another tenant must not resolve the session");
            assertTrue(sessionRepository.findByIdAndTenantIdAndHiddenAtIsNull(ids[3], ids[1]).isEmpty());

            ChatApiException foreignTenant = assertThrows(ChatApiException.class,
                    () -> chatControlPlaneService.history(ids[1], ids[2], ids[3], 20, 0));
            assertEquals(HttpStatus.NOT_FOUND, foreignTenant.getStatus());
            assertEquals("SESSION_NOT_FOUND", foreignTenant.getCode());

            ChatApiException foreignUser = assertThrows(ChatApiException.class,
                    () -> chatControlPlaneService.history(ids[0], UUID.randomUUID(), ids[3], 20, 0));
            assertEquals("SESSION_NOT_FOUND", foreignUser.getCode());

            assertTrue(chatControlPlaneService.history(ids[0], ids[2], ids[3], 20, 0).isEmpty());
        });
    }

    private void assertDenied(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        MockHttpServletResponse response = actions.andReturn().getResponse();
        int status = response.getStatus();
        assertTrue(status == 401 || status == 403,
                "unauthenticated access must be denied, got " + status);
    }

    private Tenant newTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setSlug("baseline-" + UUID.randomUUID().toString().substring(0, 8));
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenant;
    }

    private User newUser(Tenant tenant, String email) {
        User user = new User();
        user.setTenant(tenant);
        user.setEmail(email);
        user.setPasswordHash("{noop}not-a-login-path");
        user.setFullName("Baseline User");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private KnowledgeBase newKnowledgeBase(Tenant tenant, String slug) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenant(tenant);
        kb.setName(slug);
        kb.setSlug(slug);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE);
        return kb;
    }

    private Document newDocument(Tenant tenant, User user, KnowledgeBase kb, DocumentStatus status) {
        Document document = new Document();
        document.setTenantId(tenant.getId());
        document.setUploadedBy(user.getId());
        document.setKnowledgeBaseId(kb.getId());
        document.setFileName("baseline.txt");
        document.setFileType(DocumentType.TXT);
        document.setFileSizeBytes(12L);
        document.setStoragePath("baseline/" + UUID.randomUUID());
        document.setStatus(status);
        return document;
    }
}
