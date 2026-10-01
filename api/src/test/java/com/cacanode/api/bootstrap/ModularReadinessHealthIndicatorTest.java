package com.cacanode.api.bootstrap;

import com.cacanode.api.common.event.durable.ModuleEventOutbox;
import com.cacanode.api.common.event.durable.ModuleEventOutboxRepository;
import com.cacanode.api.common.event.durable.ModuleEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModularReadinessHealthIndicatorTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void deadEventsAreReportedAsDegradedWithoutRejectingServingTraffic() {
        ModuleEventOutboxRepository repository = mock(ModuleEventOutboxRepository.class);
        JdbcTemplate jdbc = migratedDatabase();
        when(repository.countByStatus(ModuleEventStatus.DEAD)).thenReturn(11L);
        when(repository.countByStatus(ModuleEventStatus.PENDING)).thenReturn(0L);
        when(repository.findTopByStatusOrderByCreatedAtAsc(ModuleEventStatus.PENDING))
                .thenReturn(Optional.empty());
        ModularReadinessHealthIndicator indicator = indicator(repository, jdbc);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("deadModuleEvents", 11L)
                .containsEntry("moduleEventOperationalState", "DEGRADED");
    }

    @Test
    void stalePendingEventsStillMakeReadinessDown() {
        ModuleEventOutboxRepository repository = mock(ModuleEventOutboxRepository.class);
        JdbcTemplate jdbc = migratedDatabase();
        ModuleEventOutbox pending = new ModuleEventOutbox();
        pending.setCreatedAt(LocalDateTime.ofInstant(CLOCK.instant().minusSeconds(31), ZoneOffset.UTC));
        when(repository.countByStatus(ModuleEventStatus.DEAD)).thenReturn(0L);
        when(repository.countByStatus(ModuleEventStatus.PENDING)).thenReturn(1L);
        when(repository.findTopByStatusOrderByCreatedAtAsc(ModuleEventStatus.PENDING))
                .thenReturn(Optional.of(pending));
        ModularReadinessHealthIndicator indicator = indicator(repository, jdbc);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("oldestPendingEventAgeSeconds", 31L);
    }

    private JdbcTemplate migratedDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        return jdbc;
    }

    private ModularReadinessHealthIndicator indicator(
            ModuleEventOutboxRepository repository, JdbcTemplate jdbc) {
        var indicator = new ModularReadinessHealthIndicator(repository, jdbc, CLOCK);
        ReflectionTestUtils.setField(indicator, "maxPendingAgeSeconds", 30L);
        return indicator;
    }
}
