package com.cacanode.api.common.event.durable;

import com.cacanode.api.tenant.api.event.UserInvitedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({DurableEventPublisher.class, DurableEventPublisherTransactionTest.JsonConfig.class})
@ActiveProfiles("test")
@Sql(statements = """
        DROP TABLE IF EXISTS module_event_outbox;
        CREATE TABLE module_event_outbox (
            event_id UUID PRIMARY KEY,
            event_type VARCHAR(160) NOT NULL,
            event_version INTEGER NOT NULL,
            payload VARCHAR NOT NULL,
            created_at TIMESTAMP NOT NULL,
            published_at TIMESTAMP,
            status VARCHAR(24) NOT NULL,
            attempts INTEGER NOT NULL,
            next_attempt_at TIMESTAMP NOT NULL,
            last_error VARCHAR
        );
        """)
class DurableEventPublisherTransactionTest {
    @Autowired
    private DurableEventPublisher publisher;
    @Autowired
    private ModuleEventOutboxRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void producerRollbackAlsoRollsBackTheOutboxInsert() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager, definition);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            publisher.publish("tenant.user.invited.v1", 1,
                    new UserInvitedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "member@example.com",
                            "Acme", "General", "MEMBER", "token", java.time.LocalDateTime.now()));
            throw new IllegalStateException("roll back producer");
        }));

        assertEquals(0, repository.count());
    }

    @Test
    void persistsPayloadAsStructuredJson() {
        UUID tenantId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UUID eventId = transaction.execute(status -> publisher.publish(
                "tenant.user.invited.v1", 1,
                new UserInvitedEvent(UUID.randomUUID(), tenantId, UUID.randomUUID(), "member@example.com",
                        "Acme", "General", "MEMBER", "token", java.time.LocalDateTime.now())));

        assertNotNull(eventId);
        ModuleEventOutbox event = repository.findById(eventId).orElseThrow();
        assertEquals(tenantId.toString(), event.getPayload().get("tenantId"));
        assertEquals("member@example.com", event.getPayload().get("email"));
        assertEquals("MEMBER", event.getPayload().get("role"));
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
