package com.app.common.inbox.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class ProcessedMessageRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ProcessedMessageRepository processedMessageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void deleteProcessedBefore_removesOnlyRowsOlderThanCutoffAndAtMostLimitRows() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID old1 = insertProcessedAt(now.minusDays(20));
        UUID old2 = insertProcessedAt(now.minusDays(15));
        UUID recent = insertProcessedAt(now.minusDays(1));

        int deleted = processedMessageRepository.deleteProcessedBefore(now.minusDays(10), 1);

        assertThat(deleted).isEqualTo(1);
        long remainingOld =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM processed_messages WHERE event_id IN (?, ?)",
                        Long.class,
                        old1,
                        old2);
        assertThat(remainingOld).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM processed_messages WHERE event_id = ?",
                                Long.class,
                                recent))
                .isEqualTo(1L);
    }

    private UUID insertProcessedAt(OffsetDateTime processedAt) {
        UUID eventId = UUID.randomUUID();
        processedMessageRepository.insertIfAbsent(
                "retention-it-consumer", eventId, "user.registered.v1");
        jdbcTemplate.update(
                "UPDATE processed_messages SET processed_at = ? WHERE event_id = ?",
                processedAt,
                eventId);
        return eventId;
    }
}
