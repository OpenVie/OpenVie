package com.openvie.api.integration.apikey;

import com.openvie.api.testsupport.PostgresTestContainer;
import com.openvie.api.tenant.enums.KnowledgeBaseStatus;
import com.openvie.api.tenant.model.KnowledgeBase;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.openvie.api.tenant.repository.UserRepository;
import com.openvie.api.tenant.repository.WorkspaceMemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The machine-to-machine credential contract exercised against real Postgres:
 * creation provisions a service member, authentication resolves through live
 * membership exactly like user JWTs, expiry and revocation shut the key off,
 * and the workspace role limits what a key may reach.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_api_keys");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceMemberRepository memberRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mockMvc;

    private UUID orgId;
    private UUID workspaceId;
    private UUID ownerId;
    private UUID knowledgeBaseId;

    @BeforeEach
    void seed() {
        transactions.executeWithoutResult(status -> {
            Organization organization = new Organization();
            organization.setName("Keys Org " + suffix());
            organization.setSlug("keys-" + suffix());
            em.persist(organization);
            em.flush();
            orgId = organization.getId();

            Tenant workspace = new Tenant();
            workspace.setOrganization(organization);
            workspace.setName("Keys Workspace");
            workspace.setSlug("keys-ws-" + suffix());
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            em.flush();
            workspaceId = workspace.getId();

            User owner = new User();
            owner.setOrganization(organization);
            owner.setEmail("owner-" + suffix() + "@keys.example");
            owner.setPasswordHash("{noop}not-used-in-this-suite");
            owner.setFullName("Owner");
            owner.setRole(OrgRole.ORG_OWNER);
            owner.setStatus(UserStatus.ACTIVE);
            em.persist(owner);
            em.flush();
            ownerId = owner.getId();

            WorkspaceMember membership = new WorkspaceMember();
            membership.setUser(owner);
            membership.setWorkspace(workspace);
            membership.setRole(WorkspaceRole.WORKSPACE_ADMIN);
            em.persist(membership);

            KnowledgeBase knowledgeBase = new KnowledgeBase();
            knowledgeBase.setTenant(workspace);
            knowledgeBase.setName("Integration Knowledge");
            knowledgeBase.setSlug("integration-" + suffix());
            knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
            em.persist(knowledgeBase);
            knowledgeBaseId = knowledgeBase.getId();
        });
    }

    @Test
    void creationProvisionsAServiceMemberAndTheSessionScopesMatchAMember() {
        var created = apiKeyService.create(orgId, ownerId,
                new ApiKeyDtos.CreateApiKeyRequest("Marketing site", workspaceId, "MEMBER", null));

        assertTrue(created.key().startsWith("ovk_"), "secrets carry the ovk_ marker");
        assertTrue(created.key().length() >= 32, "secrets carry enough entropy to resist guessing");
        assertNotNull(created.keyView().serviceUserId());
        User serviceUser = userRepository.findById(created.keyView().serviceUserId()).orElseThrow();
        assertEquals(OrgRole.MEMBER, serviceUser.getRole(), "keys are never org owners");
        assertTrue(memberRepository.findByUser_IdAndWorkspace_Id(
                        serviceUser.getId(), workspaceId).isPresent(),
                "the key's service member holds a real workspace membership");
    }

    @Test
    void authenticateResolvesLiveMembershipAndRevocationKillsTheSession() {
        var created = apiKeyService.create(orgId, ownerId,
                new ApiKeyDtos.CreateApiKeyRequest("Ops automation", workspaceId, "WORKSPACE_ADMIN", null));

        MembershipSnapshot live = apiKeyService.authenticate(created.key());
        assertNotNull(live);
        assertEquals(workspaceId, live.workspaceId());
        assertEquals(orgId, live.orgId());
        assertEquals(WorkspaceRole.WORKSPACE_ADMIN, live.workspaceRole());
        assertEquals(created.keyView().serviceUserId(), live.userId());

        apiKeyService.revoke(orgId, ownerId, created.id());
        assertNull(apiKeyService.authenticate(created.key()), "revoked keys must not authenticate");
        assertEquals(UserStatus.SUSPENDED,
                userRepository.findById(created.keyView().serviceUserId()).orElseThrow().getStatus());
    }

    @Test
    void headerAuthenticationReachesTheApiExactlyLikeAUserJwt() throws Exception {
        var created = apiKeyService.create(orgId, ownerId,
                new ApiKeyDtos.CreateApiKeyRequest("Integration key", workspaceId, "MEMBER", null));

        // Member-reachable listing: the key behaves exactly like a user JWT.
        mockMvc.perform(get("/api/v1/documents")
                        .header("Authorization", "Bearer " + created.key())
                        .param("knowledgeBaseId", knowledgeBaseId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        // Owner-only surface stays out of reach for a MEMBER key.
        mockMvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + created.key()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownSecretsExpireInsteadOfAuthenticating() {
        var created = apiKeyService.create(orgId, ownerId,
                new ApiKeyDtos.CreateApiKeyRequest("Expiring key", workspaceId, "MEMBER", null));
        assertNotNull(apiKeyService.authenticate(created.key()));

        transactions.executeWithoutResult(status -> {
            var key = em.find(ApiKey.class, created.id());
            key.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
        });
        assertNull(apiKeyService.authenticate(created.key()), "expired keys must not authenticate");

        assertNull(apiKeyService.authenticate("ovk_totallyunknowncredentialvalue"),
                "plausible but unknown secrets resolve to null");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
