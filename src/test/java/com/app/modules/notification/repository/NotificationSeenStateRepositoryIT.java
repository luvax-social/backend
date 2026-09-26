package com.app.modules.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.notification.repository.NotificationFeedRepository.Key;
import com.app.modules.notification.repository.NotificationSeenStateRepository.SeenState;

/**
 * The seen watermark against PostgreSQL: monotonic advance, the clamp, session rotation, stability
 * across reloads within a visit, and ownership.
 *
 * <p>Not transactional, so {@code now()} differs between advances as it does between requests.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@Import(NotificationSeenStateRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class NotificationSeenStateRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Duration GAP = Duration.ofMinutes(30);

    private final NotificationSeenStateRepository repository;
    private final JdbcClient jdbc;

    private UUID user;

    NotificationSeenStateRepositoryIT(NotificationSeenStateRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach
    void setUp() {
        user = user();
    }

    @Test
    void advance_firstEver_setsSeenAndLeavesPreviousEmpty() {
        Key row = notification(user, minutesAgo(5));

        SeenState state = advance(row).orElseThrow();

        assertThat(state.seen()).isEqualTo(row);
        assertThat(state.previous()).isNull();
    }

    @Test
    void advance_olderRowFromAStaleTab_neverMovesTheWatermarkBack() {
        Key older = notification(user, minutesAgo(10));
        Key newer = notification(user, minutesAgo(1));
        advance(newer);

        SeenState state = advance(older).orElseThrow();

        assertThat(state.seen()).isEqualTo(newer);
    }

    @Test
    void advance_clientTimeAheadOfTheRow_isClampedToTheRow() {
        Key row = notification(user, minutesAgo(5));

        SeenState state =
                repository
                        .advance(
                                user, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), row.id(), GAP)
                        .orElseThrow();

        assertThat(state.seen()).isEqualTo(row);
    }

    @Test
    void advance_rowOfAnotherAccount_writesNothing() {
        Key foreign = notification(user(), minutesAgo(1));

        assertThat(advance(foreign)).isEmpty();
        assertThat(repository.find(user)).isEmpty();
    }

    @Test
    void advance_withinTheSessionGap_keepsPreviousStableAcrossReloads() {
        Key first = notification(user, minutesAgo(30));
        Key second = notification(user, minutesAgo(20));
        Key third = notification(user, minutesAgo(1));
        advance(first);
        endVisit();
        SeenState opened = advance(second).orElseThrow();

        SeenState reload = advance(second).orElseThrow();
        SeenState laterInTheVisit = advance(third).orElseThrow();

        assertThat(opened.previous()).isEqualTo(first);
        assertThat(reload.previous()).isEqualTo(first);
        assertThat(laterInTheVisit.previous()).isEqualTo(first);
        assertThat(laterInTheVisit.seen()).isEqualTo(third);
        assertThat(repository.find(user)).contains(laterInTheVisit);
    }

    @Test
    void advance_afterTheSessionGap_rotatesPreviousToTheLastSeen() {
        Key first = notification(user, minutesAgo(30));
        Key second = notification(user, minutesAgo(1));
        advance(first);
        endVisit();
        advance(first);
        endVisit();

        SeenState state = advance(second).orElseThrow();

        assertThat(state.previous()).isEqualTo(first);
        assertThat(state.seen()).isEqualTo(second);
    }

    private Optional<SeenState> advance(Key row) {
        return repository.advance(user, row.activityAt(), row.id(), GAP);
    }

    private void endVisit() {
        jdbc.sql(
                        "UPDATE notification_seen_states SET advanced_at = now() - interval '31"
                                + " minutes' WHERE user_id = :user")
                .param("user", user)
                .update();
    }

    private Key notification(UUID recipient, OffsetDateTime activityAt) {
        UUID id =
                jdbc.sql(
                                "INSERT INTO notifications (recipient_id, type, category,"
                                        + " activity_at) VALUES (:recipient, 'warning', 'system',"
                                        + " :activityAt) RETURNING id")
                        .param("recipient", recipient)
                        .param("activityAt", activityAt)
                        .query(UUID.class)
                        .single();
        return new Key(
                jdbc.sql("SELECT activity_at FROM notifications WHERE id = :id")
                        .param("id", id)
                        .query(OffsetDateTime.class)
                        .single(),
                id);
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private UUID user() {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.sql("INSERT INTO users (username, email) VALUES (:name, :email) RETURNING id")
                .param("name", name)
                .param("email", name + "@example.com")
                .query(UUID.class)
                .single();
    }
}
