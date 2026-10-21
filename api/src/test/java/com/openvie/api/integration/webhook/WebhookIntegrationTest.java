package com.openvie.api.integration.webhook;

import com.openvie.api.integration.IntegrationCredentials;
import com.openvie.api.notification.service.CredentialCipher;
import com.openvie.api.testsupport.PostgresTestContainer;
import com.openvie.api.tenant.api.OrgRole;
import com.openvie.api.tenant.api.TenantStatus;
import com.openvie.api.tenant.api.UserStatus;
import com.openvie.api.tenant.api.WorkspaceRole;
import com.openvie.api.tenant.enums.WorkspaceVisibility;
import com.openvie.api.tenant.model.Organization;
import com.openvie.api.tenant.model.Tenant;
import com.openvie.api.tenant.model.User;
import com.openvie.api.tenant.model.WorkspaceMember;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhook contract against real Postgres plus a local HTTP receiver:
 * events journal transactionally, the dispatcher POSTs the envelope signed
 * with HMAC-SHA256, 2xx closes the delivery, and failures exhaust retries into
 * an explicit DEAD status that the owner can inspect.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        // Deterministic (off-schedule) dispatch for tests; the cipher must be
        // able to protect webhook secrets, exactly like channel credentials.
        "app.webhooks.dispatch-interval-ms=3600000",
        "app.notifications.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@ActiveProfiles("test")
class WebhookIntegrationTest {

    private static final String JDBC_URL =
            PostgresTestContainer.createDatabase("openvie_webhooks");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", PostgresTestContainer::username);
        registry.add("spring.datasource.password", PostgresTestContainer::password);
    }

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private WebhookEventPublisher webhookEventPublisher;

    @Autowired
    private WebhookDeliveryDispatcher dispatcher;

    @Autowired
    private CredentialCipher cipher;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactions;

    private HttpServer receiver;
    /** Captured requests: signing header + body, read once by the handler thread. */
    private final record Received(String signature, String body) {
    }
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final AtomicInteger alwaysFail = new AtomicInteger(0);

    private UUID orgId;
    private UUID workspaceId;
    private UUID ownerId;

    @BeforeEach
    void seedAndListen() throws IOException {
        transactions.executeWithoutResult(status -> {
            Organization organization = new Organization();
            organization.setName("Webhooks Org " + suffix());
            organization.setSlug("hooks-" + suffix());
            em.persist(organization);
            em.flush();
            orgId = organization.getId();

            Tenant workspace = new Tenant();
            workspace.setOrganization(organization);
            workspace.setName("Webhooks Workspace");
            workspace.setSlug("hooks-ws-" + suffix());
            workspace.setStatus(TenantStatus.ACTIVE);
            workspace.setVisibility(WorkspaceVisibility.PUBLIC);
            em.persist(workspace);
            workspaceId = workspace.getId();

            User owner = new User();
            owner.setOrganization(organization);
            owner.setEmail("owner-" + suffix() + "@hooks.example");
            owner.setPasswordHash("{noop}not-used-in-this-suite");
            owner.setFullName("Owner");
            owner.setRole(OrgRole.ORG_OWNER);
            owner.setStatus(UserStatus.ACTIVE);
            em.persist(owner);
            ownerId = owner.getId();

            WorkspaceMember membership = new WorkspaceMember();
            membership.setUser(owner);
            membership.setWorkspace(workspace);
            membership.setRole(WorkspaceRole.WORKSPACE_ADMIN);
            em.persist(membership);
        });

        received.clear();
        alwaysFail.set(0);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/accept", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            int status = alwaysFail.get() > 0 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            received.add(new Received(
                    exchange.getRequestHeaders().getFirst("X-OpenVie-Signature"),
                    new String(bodyBytes, StandardCharsets.UTF_8)));
        });
        receiver.start();
    }

    @AfterEach
    void stopListening() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    @Test
    void deliveredBodiesCarryTheEnvelopeAndAValidSignature() throws Exception {
        var created = webhookService.create(orgId, workspaceId, ownerId,
                new WebhookDtos.CreateWebhookRequest("DMS bridge",
                        receiverUrl("/accept"), null));
        assertTrue(created.secret().startsWith("whsec_"), "secrets carry the whsec_ marker");

        webhookEventPublisher.publish(workspaceId, "document.status.changed",
                UUID.randomUUID(),
                Map.of(
                        "document_id", UUID.randomUUID().toString(),
                        "status", "COMPLETED",
                        "chunk_count", 12));
        dispatcher.dispatchDue();

        assertEquals(1, received.size(), "one POST for one journaled event");
        Received delivery = received.poll();
        String requestBody = delivery.body();

        // Event envelope
        assertTrue(requestBody.contains("\"type\":\"document.status.changed\""), requestBody);
        assertTrue(requestBody.contains("\"workspace_id\":\"" + workspaceId + "\""), requestBody);
        assertTrue(requestBody.contains("\"data\""), requestBody);
        assertTrue(requestBody.contains("COMPLETED"), requestBody);

        // Signature: t=<unix>,v1=<hex> over "<t>.<body>" with the created secret
        String signature = delivery.signature();
        assertNotNull(signature, "deliveries must be signed");
        int tIndex = signature.indexOf("t=");
        int vIndex = signature.lastIndexOf("v1=");
        assertTrue(tIndex >= 0 && vIndex > tIndex, signature);
        String timestamp = extractBetween(signature, "t=", vIndex);
        String expected = "t=" + timestamp + ",v1="
                + IntegrationCredentials.hmacSha256Hex(created.secret(), timestamp + "." + requestBody);
        assertEquals(expected, signature, "the receiver must be able to verify the body");
    }

    @Test
    void deliveriesFilterEndpointsByTheirEventSubscription() {
        var filtered = webhookService.create(orgId, workspaceId, ownerId,
                new WebhookDtos.CreateWebhookRequest("Workflow only",
                        receiverUrl("/accept"),
                        List.of("workflow.approved")));
        webhookEventPublisher.publish(workspaceId, "document.status.changed",
                UUID.randomUUID(), java.util.Map.of("status", "COMPLETED"));
        webhookEventPublisher.publish(workspaceId, "workflow.approved",
                UUID.randomUUID(), java.util.Map.of("decision", "APPROVE"));
        dispatcher.dispatchDue();

        var deliveries = webhookService.deliveries(orgId, filtered.id());
        assertEquals(1, deliveries.size(), "the filtered endpoint only saw the subscribed event");
        assertEquals("workflow.approved", deliveries.get(0).eventType());
        assertEquals("DELIVERED", deliveries.get(0).status());
    }

    @Test
    void failuresRetainTheDeliveryForRetryUntilTheCeilingTurnsItDead() {
        alwaysFail.set(1);
        var created = webhookService.create(orgId, workspaceId, ownerId,
                new WebhookDtos.CreateWebhookRequest("Failing bridge",
                        receiverUrl("/accept"), null));
        webhookService.testSend(orgId, ownerId, created.id());

        dispatcher.dispatchDue();
        var deliveries = webhookService.deliveries(orgId, created.id());
        assertEquals(1, deliveries.size());
        assertEquals("PENDING", deliveries.get(0).status(),
                "the first failure must schedule a retry, never drop the event");
        assertEquals(500, deliveries.get(0).lastHttpStatus());
        assertEquals(1, deliveries.get(0).attemptCount());
        assertFalse(deliveries.get(0).nextAttemptAt().isBefore(LocalDateTime.now()),
                "retry is deferred by backoff");

        // Force the retry ceiling: the 5th failed attempt must be marked DEAD.
        transactions.executeWithoutResult(status -> {
            var query = em.createQuery(
                    "select d from WebhookDelivery d where d.endpointId = :endpoint", WebhookDelivery.class);
            query.setParameter("endpoint", created.id());
            var row = (WebhookDelivery) query.getSingleResult();
            row.setAttemptCount(WebhookDelivery.MAX_ATTEMPTS - 1);
            row.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            em.merge(row);
        });
        dispatcher.dispatchDue();
        var settled = webhookService.deliveries(orgId, created.id());
        assertEquals("DEAD", settled.get(0).status(),
                "exhausted retries must leave an explicit terminal state");
    }

    private String receiverUrl(String path) {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + path;
    }

    private static String extractBetween(String value, String marker, int end) {
        int start = value.indexOf(marker) + marker.length();
        return value.substring(start, end).trim().replace(",", "");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
