package com.cacanode.api.bootstrap;

import com.cacanode.api.common.event.durable.ModuleEventOutboxRepository;
import com.cacanode.api.common.event.durable.ModuleEventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component("modularReadiness")
@RequiredArgsConstructor
public class ModularReadinessHealthIndicator implements HealthIndicator {
    private final ModuleEventOutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Value("${app.module-events.readiness-max-pending-age-seconds:30}")
    private long maxPendingAgeSeconds;

    @Override
    public Health health() {
        try {
            boolean migrationComplete = migrationComplete();
            long dead = outboxRepository.countByStatus(ModuleEventStatus.DEAD);
            long pending = outboxRepository.countByStatus(ModuleEventStatus.PENDING);
            long held = outboxRepository.countByStatus(ModuleEventStatus.NO_CHANNEL);
            long oldestPendingAgeSeconds = outboxRepository
                    .findTopByStatusOrderByCreatedAtAsc(ModuleEventStatus.PENDING)
                    .map(event -> Math.max(0, Duration.between(
                            event.getCreatedAt(), LocalDateTime.now(clock)).toSeconds()))
                    .orElse(0L);
            boolean ready = migrationComplete && oldestPendingAgeSeconds <= maxPendingAgeSeconds;
            Health.Builder builder = ready ? Health.up() : Health.down();
            return builder.withDetail("baselineMigrationsComplete", migrationComplete)
                    .withDetail("pendingModuleEvents", pending)
                    .withDetail("heldModuleEventsNoChannel", held)
                    .withDetail("deadModuleEvents", dead)
                    .withDetail("moduleEventOperationalState", dead == 0 ? "HEALTHY" : "DEGRADED")
                    .withDetail("oldestPendingEventAgeSeconds", oldestPendingAgeSeconds)
                    .build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }

    private boolean migrationComplete() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('1', '2') AND success = TRUE
                """, Integer.class);
        return applied != null && applied == 2;
    }
}
