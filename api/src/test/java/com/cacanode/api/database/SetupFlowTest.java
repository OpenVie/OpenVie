package com.cacanode.api.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.common.exception.custom.ConflictException;
import com.cacanode.api.tenant.api.OrgRole;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.cacanode.api.tenant.service.SetupService;
import com.cacanode.api.tenant.service.SetupService.SetupCommand;
import com.cacanode.api.testsupport.PostgresTestContainer;

/**
 * One-time installation setup against a real, empty PostgreSQL database.
 *
 * <p>Kept separate from {@link FreshPostgresBaselineTest} because setup is only
 * offered while the installation has no accounts, and the baseline suite creates
 * accounts to prove workspace isolation. The methods run in declaration order:
 * the empty-installation assertions must precede the one that claims it.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SetupFlowTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_setup");
    private static final String OWNER_PASSWORD = "a-fresh-long-password";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }
    @Autowired
    private SetupService setupService;

    @Autowired
    private TenantIdentityApi identityApi;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Order(1)
    void setupRejectsWeakAndPublishedPasswordsBeforeClaimingTheInstall() {
        assertTrue(setupService.required(), "an empty installation must offer setup");

        assertThrows(BadRequestException.class, () -> setupService.complete(new SetupCommand(
                "Weak Org", "Someone", "weak@acme.test", "short", false)));
        assertThrows(BadRequestException.class, () -> setupService.complete(new SetupCommand(
                "Seed Org", "Someone", "seed@acme.test", "Cacanode@123", false)));
        assertThrows(BadRequestException.class, () -> setupService.complete(new SetupCommand(
                "   ", "Someone", "blank@acme.test", OWNER_PASSWORD, false)));

        assertTrue(setupService.required(),
                "a rejected setup must leave the installation unclaimed");
        assertEquals(0, jdbc.queryForObject("select count(*) from organizations", Integer.class).intValue());
    }

    @Test
    @Order(2)
    void concurrentSetupCreatesExactlyOneOrganization() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Thread> racers = List.of(
                new Thread(() -> attempt(ready, start, "Racer One", "racer-one@acme.test",
                        succeeded, refused)),
                new Thread(() -> attempt(ready, start, "Racer Two", "racer-two@acme.test",
                        succeeded, refused)));
        racers.forEach(Thread::start);
        ready.await();
        start.countDown();
        for (Thread racer : racers) {
            racer.join(30_000);
        }

        assertEquals(1, succeeded.get(), "exactly one setup submission may win");
        assertEquals(1, refused.get(), "the loser must be refused, not silently ignored");
        assertEquals(1, jdbc.queryForObject("select count(*) from organizations", Integer.class).intValue(),
                "concurrent setup must never create a second organization");
    }

    @Test
    @Order(3)
    void theWinningSetupCreatedOrganizationDefaultWorkspaceAndOwner() {
        assertFalse(setupService.required(), "setup must close after the first account");

        List<Map<String, Object>> workspaces = jdbc.queryForList(
                "select slug, is_default, visibility, status from tenants");
        assertEquals(1, workspaces.size());
        assertEquals("general", workspaces.get(0).get("slug"));
        assertEquals(Boolean.TRUE, workspaces.get(0).get("is_default"));
        assertEquals("PUBLIC", workspaces.get(0).get("visibility"));
        assertEquals("ACTIVE", workspaces.get(0).get("status"));

        // The claimant is an organization owner AND an admin of the default workspace.
        assertEquals(List.of("ORG_OWNER"),
                jdbc.queryForList("select role from users order by email", String.class));
        assertEquals(List.of("WORKSPACE_ADMIN"), jdbc.queryForList(
                "select wm.role from workspace_members wm join users u on u.id = wm.user_id",
                String.class));

        // Provisioning created the KB and assistant the workspace needs to ingest.
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_bases", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject("select count(*) from chatbots", Integer.class).intValue());
    }

    @Test
    @Order(4)
    void setupIsRefusedOnceAnyAccountExists() {
        assertThrows(ConflictException.class, () -> setupService.complete(new SetupCommand(
                "Second Org", "Late Comer", "late@acme.test", "another-fresh-password", false)));
        assertEquals(1, jdbc.queryForObject("select count(*) from organizations", Integer.class).intValue(),
                "a refused setup must not leave an organization behind");
    }

    @Test
    @Order(5)
    void registrationStatusReflectsTheInstalledState() {
        var status = identityApi.registrationStatus();
        assertFalse(status.setupRequired(), "an installed system must not offer setup again");
        assertFalse(status.selfRegistrationAllowed(),
                "self-registration stays off until the owner enables it");
    }

    @Test
    @Order(6)
    void authenticateResolvesTheDefaultWorkspaceAndRejectsBadCredentials() {
        String winnerEmail = jdbc.queryForObject(
                "select email from users order by email limit 1", String.class);

        var authenticated = identityApi.authenticate(winnerEmail, OWNER_PASSWORD);

        assertNotNull(authenticated);
        assertEquals(OrgRole.ORG_OWNER, authenticated.identity().orgRole());
        assertEquals(WorkspaceRole.WORKSPACE_ADMIN, authenticated.defaultWorkspaceRole());
        UUID defaultWorkspace = UUID.fromString(jdbc.queryForObject(
                "select id from tenants where is_default", String.class));
        assertEquals(defaultWorkspace, authenticated.defaultWorkspaceId());

        assertNull(identityApi.authenticate(winnerEmail, "wrong-password"),
                "a wrong password must not authenticate");
        assertNull(identityApi.authenticate("nobody@acme.test", OWNER_PASSWORD),
                "an unknown account must not authenticate");
    }

    @Test
    @Order(7)
    void theOwnerCanReachItsWorkspaceButNotAnInventedOne() {
        String winnerEmail = jdbc.queryForObject(
                "select email from users order by email limit 1", String.class);
        UUID ownerId = jdbc.queryForObject(
                "select id from users where email = ?", UUID.class, winnerEmail);
        UUID workspaceId = UUID.fromString(jdbc.queryForObject(
                "select id from tenants where is_default", String.class));

        var membership = identityApi.requireMembership(ownerId, workspaceId);
        assertEquals(WorkspaceRole.WORKSPACE_ADMIN, membership.workspaceRole());
        assertTrue(membership.isWorkspaceAdmin());

        assertThrows(com.cacanode.api.common.exception.custom.UnauthorizedException.class,
                () -> identityApi.requireMembership(ownerId, UUID.randomUUID()),
                "a non-existent workspace must not resolve");
    }

    private void attempt(
            CountDownLatch ready, CountDownLatch start, String name, String email,
            AtomicInteger succeeded, AtomicInteger refused) {
        ready.countDown();
        try {
            start.await();
            setupService.complete(new SetupCommand(name, "Racer", email, OWNER_PASSWORD, false));
            succeeded.incrementAndGet();
        } catch (ConflictException expected) {
            refused.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception unexpected) {
            throw new IllegalStateException("unexpected setup failure", unexpected);
        }
    }
}
