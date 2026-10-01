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
import org.springframework.dao.DataIntegrityViolationException;
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
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import com.cacanode.api.document.api.DocumentApi;
import com.cacanode.api.document.enums.DocumentStatus;
import com.cacanode.api.document.enums.DocumentType;
import com.cacanode.api.document.model.Document;
import com.cacanode.api.tenant.enums.ChatbotStatus;
import com.cacanode.api.tenant.enums.KnowledgeBaseStatus;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.cacanode.api.tenant.api.UserStatus;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.enums.WorkspaceVisibility;
import com.cacanode.api.tenant.api.TenantStatus;
import com.cacanode.api.tenant.model.Chatbot;
import com.cacanode.api.tenant.model.KnowledgeBase;
import com.cacanode.api.tenant.model.Organization;
import com.cacanode.api.tenant.model.Tenant;
import com.cacanode.api.tenant.model.User;
import com.cacanode.api.tenant.model.WorkspaceMember;
import com.cacanode.api.ai.model.ModelConfigVersion;
import com.cacanode.api.testsupport.PostgresTestContainer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Proves the v0.2 database contract against a real, empty PostgreSQL database:
 * the reviewed Flyway baseline migrates from scratch, Hibernate validates the
 * entities against it, the workspace/membership invariants hold, and no removed
 * surface (2FA, widget, billing, platform) is reachable.
 *
 * <p>One-time setup semantics need an installation with no accounts, so they are
 * covered by {@link SetupFlowTest} against its own database.
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
    private TenantIdentityApi identityApi;

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
                "organizations", "tenants", "users", "workspace_members", "invitations",
                "knowledge_bases", "chatbots", "model_config_versions",
                "notification_channels", "refresh_tokens",
                "chat_sessions", "chat_messages", "chat_turns",
                "documents", "internal_event_outbox", "internal_event_inbox",
                "module_event_outbox", "module_event_inbox",
                "audit_logs", "notifications");
        List<String> actual = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_name <> 'flyway_schema_history'",
                String.class);
        assertEquals(expected, new HashSet<>(actual),
                "fresh install must contain exactly the retained v0.2 tables");
        assertFalse(actual.contains("login_2fa_state"),
                "password-only login leaves no 2FA state table behind");
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

    // Constraint assertions use raw SQL: JdbcTemplate translates SQLExceptions to
    // Spring's DataIntegrityViolationException, so these tests assert the database
    // rule itself rather than Hibernate's flush-time exception type.
    @Test
    void workspaceSlugIsUniqueOnlyWithinItsOrganization() {
        String slug = "shared-slug-" + suffix();
        UUID firstOrg = persistOrganization("Slug Scope One");
        UUID secondOrg = persistOrganization("Slug Scope Two");

        insertWorkspace(firstOrg, slug, "First");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertWorkspace(firstOrg, slug, "Duplicate"),
                "two workspaces in one organization cannot share a slug");
        assertDoesNotThrow(() -> insertWorkspace(secondOrg, slug, "Same slug, other organization"),
                "distinct organizations may reuse a workspace slug");
    }

    @Test
    void aUserHoldsAtMostOneMembershipPerWorkspace() {
        UUID orgId = persistOrganization("Membership Uniqueness");
        String workspaceId = "solo-" + suffix();
        insertWorkspace(orgId, workspaceId, "Solo");
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, org_id, email, password_hash, role, status) "
                        + "values (?, ?, ?, 'x', 'MEMBER', 'ACTIVE')",
                userId, orgId, "dupe-" + suffix() + "@baseline.test");
        UUID workspace = workspaceId(workspaceId);

        jdbc.update("insert into workspace_members (user_id, tenant_id, role) values (?, ?, 'MEMBER')",
                userId, workspace);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into workspace_members (user_id, tenant_id, role) "
                                + "values (?, ?, 'WORKSPACE_ADMIN')",
                        userId, workspace),
                "a second membership row for the same user and workspace must be refused");
    }

    @Test
    void roleConstraintsRejectUnknownWireValues() {
        UUID orgId = persistOrganization("Constraint Org");
        UUID[] ids = new UUID[2];
        transactions.executeWithoutResult(status -> {
            Tenant workspace = newWorkspace(orgId, "constrained-" + suffix(), "Constrained");
            User user = newUser(orgId, "constrained-" + suffix() + "@baseline.test");
            em.persist(workspace);
            em.persist(user);
            em.flush();
            ids[0] = user.getId();
            ids[1] = workspace.getId();
        });

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into users (org_id, email, password_hash, role, status) "
                                + "values (?, ?, 'x', 'SUPERUSER', 'ACTIVE')",
                        orgId, "bad-org-role-" + suffix() + "@baseline.test"),
                "users.role is limited to the organization roles");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into workspace_members (user_id, tenant_id, role) "
                                + "values (?, ?, 'OWNER')",
                        ids[0], ids[1]),
                "workspace_members.role is limited to WORKSPACE_ADMIN and MEMBER");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into tenants (org_id, name, slug, status, visibility, is_default) "
                                + "values (?, 'x', ?, 'ACTIVE', 'SECRET', false)",
                        orgId, "bad-visibility-" + suffix()),
                "visibility is limited to PUBLIC and PRIVATE");
    }

    @Test
    void exactlyOneDefaultWorkspacePerOrganization() {
        UUID orgId = persistOrganization("Default Guard");
        insertWorkspace(orgId, "default-" + suffix(), "Default", true);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertWorkspace(orgId, "second-default-" + suffix(), "Second", true),
                "an organization may have only one default workspace");
        // Non-default workspaces are unlimited.
        assertDoesNotThrow(() -> insertWorkspace(orgId, "plain-" + suffix(), "Plain", false));
    }

    @Test
    void membershipIsRequiredAndIsScopedToTheOwningOrganization() {
        UUID[] ids = new UUID[4];
        transactions.executeWithoutResult(status -> {
            Organization orgA = newOrganization("Membership A");
            Organization orgB = newOrganization("Membership B");
            em.persist(orgA);
            em.persist(orgB);
            em.flush();
            Tenant workspaceA = newWorkspace(orgA.getId(), "ws-a-" + suffix(), "Alpha");
            Tenant workspaceB = newWorkspace(orgB.getId(), "ws-b-" + suffix(), "Beta");
            em.persist(workspaceA);
            em.persist(workspaceB);
            User userA = newUser(orgA.getId(), "member-a-" + suffix() + "@baseline.test");
            em.persist(userA);
            em.flush();
            em.persist(newMembership(userA.getId(), workspaceA.getId(), WorkspaceRole.WORKSPACE_ADMIN));
            em.flush();
            ids[0] = userA.getId();
            ids[1] = workspaceA.getId();
            ids[2] = workspaceB.getId();
            ids[3] = orgA.getId();
        });
        em.clear();

        MembershipSnapshot granted = identityApi.requireMembership(ids[0], ids[1]);
        assertEquals(ids[3], granted.orgId());
        assertTrue(granted.isWorkspaceAdmin());
        assertEquals(WorkspaceRole.WORKSPACE_ADMIN, granted.workspaceRole());

        assertThrows(UnauthorizedException.class,
                () -> identityApi.requireMembership(ids[0], ids[2]),
                "a user must not resolve a workspace in another organization");
        assertThrows(UnauthorizedException.class,
                () -> identityApi.requireMembership(UUID.randomUUID(), ids[1]),
                "an unknown account must not resolve any workspace");
    }

    @Test
    void removedAndPublicSurfacesHaveTheExpectedRoutes() {
        Set<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    Set<String> values = new HashSet<String>();
                    if (info.getPathPatternsCondition() != null) {
                        values.addAll(info.getPathPatternsCondition().getPatternValues());
                    }
                    return values.stream();
                }).collect(java.util.stream.Collectors.toSet());

        assertTrue(patterns.contains("/api/v1/auth/login"), "retained login route must exist");
        // Email is optional and login is password-only: the challenge routes are gone.
        for (String removed : List.of(
                "/api/v1/auth/verify-login-2fa", "/api/v1/auth/resend-login-2fa")) {
            assertFalse(patterns.contains(removed), "removed 2FA route must not exist: " + removed);
        }
        for (String removed : List.of(
                "/api/v1/widget/chat", "/api/v1/external/tickets", "/api/v1/external/chat",
                "/api/v1/public/billing/payos/webhook", "/api/v1/platform")) {
            assertFalse(patterns.stream().anyMatch(pattern -> pattern.startsWith(removed)),
                    "removed surface must not be reachable: " + removed);
        }
    }

    @Test
    void unauthenticatedRequestsAreDeniedForDocumentsChatAndWorkspaces() throws Exception {
        assertDenied(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/documents")));
        assertDenied(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/chat/sessions")
                .contentType("application/json").content("{}")));
        assertDenied(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces")));
    }

    @Test
    void citationValidationRejectsForgedForeignAndIncompleteDocuments() {
        UUID[] workspaceIds = new UUID[2];
        KnowledgeBase[] knowledgeBases = new KnowledgeBase[2];
        Document[] completed = new Document[1];
        Document[] pending = new Document[1];

        transactions.executeWithoutResult(status -> {
            Tenant workspaceA = newWorkspaceWithOrganization("Citation A");
            Tenant workspaceB = newWorkspaceWithOrganization("Citation B");
            em.persist(workspaceA.getOrganization());
            em.persist(workspaceB.getOrganization());
            em.persist(workspaceA);
            em.persist(workspaceB);
            em.flush();
            workspaceIds[0] = workspaceA.getId();
            workspaceIds[1] = workspaceB.getId();
            knowledgeBases[0] = newKnowledgeBase(workspaceA, "kb-a-" + suffix());
            knowledgeBases[1] = newKnowledgeBase(workspaceB, "kb-b-" + suffix());
            em.persist(knowledgeBases[0]);
            em.persist(knowledgeBases[1]);
            User uploader = newUser(workspaceA.getOrganization().getId(),
                    "citation-a-" + suffix() + "@baseline.test");
            em.persist(uploader);
            em.flush();
            completed[0] = newDocument(workspaceA, uploader, knowledgeBases[0], DocumentStatus.COMPLETED);
            pending[0] = newDocument(workspaceA, uploader, knowledgeBases[0], DocumentStatus.PENDING);
            em.persist(completed[0]);
            em.persist(pending[0]);
            em.flush();
        });
        em.clear();

        UUID completedDocumentId = completed[0].getId();
        assertDoesNotThrow(() -> documentApi.validateCitations(
                workspaceIds[0], knowledgeBases[0].getId(), List.of(completedDocumentId)),
                "a completed document of the same workspace and knowledge base is a valid citation");

        assertThrows(BadRequestException.class, () -> documentApi.validateCitations(
                        workspaceIds[1], knowledgeBases[1].getId(), List.of(completedDocumentId)),
                "another workspace may not cite this workspace's document");
        assertThrows(BadRequestException.class, () -> documentApi.validateCitations(
                        workspaceIds[0], knowledgeBases[0].getId(), List.of(UUID.randomUUID())),
                "forged citation identifiers must be rejected");
        assertThrows(BadRequestException.class, () -> documentApi.validateCitations(
                        workspaceIds[0], knowledgeBases[0].getId(), List.of(pending[0].getId())),
                "only completed documents may back a citation");
    }

    @Test
    void chatSessionAccessIsScopedToWorkspaceAndOwner() {
        UUID[] ids = new UUID[4];
        transactions.executeWithoutResult(status -> {
            Tenant workspaceA = newWorkspaceWithOrganization("Session A");
            Tenant workspaceB = newWorkspaceWithOrganization("Session B");
            em.persist(workspaceA.getOrganization());
            em.persist(workspaceB.getOrganization());
            em.persist(workspaceA);
            em.persist(workspaceB);
            em.flush();
            User owner = newUser(workspaceA.getOrganization().getId(),
                    "session-owner-" + suffix() + "@baseline.test");
            em.persist(owner);
            KnowledgeBase kb = newKnowledgeBase(workspaceA, "kb-session-" + suffix());
            em.persist(kb);
            ModelConfigVersion model = em.createQuery(
                            "select m from ModelConfigVersion m where m.status = :status",
                            ModelConfigVersion.class)
                    .setParameter("status", com.cacanode.api.ai.enums.ModelConfigStatus.ACTIVE)
                    .getSingleResult();
            Chatbot chatbot = new Chatbot();
            chatbot.setTenant(workspaceA);
            chatbot.setKnowledgeBase(kb);
            chatbot.setModelConfigVersionId(model.getId());
            chatbot.setDisplayName("Baseline Assistant");
            chatbot.setWelcomeMessage("hello");
            chatbot.setSafeInstructions("internal");
            chatbot.setStatus(ChatbotStatus.ACTIVE);
            em.persist(chatbot);
            em.flush();

            ChatSession session = new ChatSession();
            session.setTenantId(workspaceA.getId());
            session.setUserId(owner.getId());
            session.setChatbotId(chatbot.getId());
            session.setKnowledgeBaseId(kb.getId());
            session.setStatus(ChatSessionStatus.OPEN);
            session.setChannel(ChatChannel.EMPLOYEE_PLAYGROUND);
            em.persist(session);
            em.flush();

            ids[0] = workspaceA.getId();
            ids[1] = workspaceB.getId();
            ids[2] = owner.getId();
            ids[3] = session.getId();
        });
        em.clear();

        // findForUpdate takes a pessimistic lock, so the assertions run inside a
        // transaction like the setup above.
        transactions.executeWithoutResult(status -> {
            assertTrue(sessionRepository.findForUpdate(ids[3], ids[0]).isPresent());
            assertTrue(sessionRepository.findForUpdate(ids[3], ids[1]).isEmpty(),
                    "another workspace must not resolve the session");
            assertTrue(sessionRepository.findByIdAndTenantIdAndHiddenAtIsNull(ids[3], ids[1]).isEmpty());

            ChatApiException foreignWorkspace = assertThrows(ChatApiException.class,
                    () -> chatControlPlaneService.history(ids[1], ids[2], ids[3], 20, 0));
            assertEquals(HttpStatus.NOT_FOUND, foreignWorkspace.getStatus());
            assertEquals("SESSION_NOT_FOUND", foreignWorkspace.getCode());

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

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID persistOrganization(String name) {
        UUID[] id = new UUID[1];
        transactions.executeWithoutResult(status -> {
            Organization organization = newOrganization(name);
            em.persist(organization);
            em.flush();
            id[0] = organization.getId();
        });
        return id[0];
    }

    private Organization newOrganization(String name) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setSlug("baseline-" + suffix());
        return organization;
    }

    private Tenant newWorkspace(UUID orgId, String slug, String name) {
        return newWorkspace(orgId, slug, name, false);
    }

    private Tenant newWorkspace(UUID orgId, String slug, String name, boolean isDefault) {
        Tenant workspace = new Tenant();
        workspace.setOrganization(em.getReference(Organization.class, orgId));
        workspace.setName(name);
        workspace.setSlug(slug);
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        workspace.setDefaultWorkspace(isDefault);
        return workspace;
    }

    /** Builds a workspace with a detached organization so both can be persisted together. */
    private Tenant newWorkspaceWithOrganization(String name) {
        Tenant workspace = new Tenant();
        workspace.setOrganization(newOrganization(name));
        workspace.setName(name);
        workspace.setSlug("baseline-" + suffix());
        workspace.setStatus(TenantStatus.ACTIVE);
        workspace.setVisibility(WorkspaceVisibility.PUBLIC);
        return workspace;
    }
    /** Raw-SQL insert so constraint violations surface as Spring exceptions. */
    private void insertWorkspace(UUID orgId, String slug, String name) {
        insertWorkspace(orgId, slug, name, false);
    }

    private void insertWorkspace(UUID orgId, String slug, String name, boolean isDefault) {
        jdbc.update("insert into tenants (org_id, name, slug, status, visibility, is_default) "
                        + "values (?, ?, ?, 'ACTIVE', 'PUBLIC', ?)",
                orgId, name, slug, isDefault);
    }

    private UUID workspaceId(String slug) {
        return jdbc.queryForObject("select id from tenants where slug = ?", UUID.class, slug);
    }

    private User newUser(UUID orgId, String email) {
        User user = new User();
        user.setOrganization(em.getReference(Organization.class, orgId));
        user.setEmail(email);
        user.setPasswordHash("{noop}not-a-login-path");
        user.setFullName("Baseline User");
        user.setRole(OrgRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private WorkspaceMember newMembership(UUID userId, UUID workspaceId, WorkspaceRole role) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setUser(em.getReference(User.class, userId));
        membership.setWorkspace(em.getReference(Tenant.class, workspaceId));
        membership.setRole(role);
        return membership;
    }

    private KnowledgeBase newKnowledgeBase(Tenant workspace, String slug) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenant(workspace);
        kb.setName(slug);
        kb.setSlug(slug);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE);
        return kb;
    }

    private Document newDocument(Tenant workspace, User user, KnowledgeBase kb, DocumentStatus status) {
        Document document = new Document();
        document.setTenantId(workspace.getId());
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
