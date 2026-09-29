package com.app.modules.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.entity.AdminAction;
import com.app.modules.admin.enums.AdminActionType;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

/**
 * The port of {@code AdminActionKeysetRowLossIT} to the ClickHouse replica: rows that share one
 * recording time are paged so that the page size cuts into the tie group, and every row must come
 * back exactly once, on ClickHouse alone and with the pages alternating between the stores.
 *
 * <p>The identifiers are chosen so the two stores' native UUID orders disagree. ClickHouse orders a
 * UUID by its second half first, PostgreSQL by its bytes, and a listing that ordered by the native
 * identifier would drop and repeat rows the moment a cursor crossed from one store to the other.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false"
        })
@Testcontainers
class AdminActionClickHouseKeysetRowLossIT {

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "admin-keyset-it-secret-32-characters-minimum!!");
        r.add("JWT_ISSUER", () -> "https://admin-keyset.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    // The two orders disagree for these: ClickHouse would put the first ahead of the second, and
    // PostgreSQL the second ahead of the first.
    private static final List<UUID> TIED_IDS =
            List.of(
                    UUID.fromString("00000000-0000-0000-ffff-000000000000"),
                    UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                    UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    UUID.fromString("88888888-0000-0000-0000-000000000001"),
                    UUID.fromString("00000000-0000-0000-0000-000000000009"));

    // Microsecond precision on purpose: the cursor carries microseconds, and a comparison that
    // truncated them would split the tie group.
    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 123_456_000, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    @Autowired private AdminActionAnalyticsRepository analyticsRepository;
    @Autowired private AdminActionRepository adminActionRepository;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private JdbcClient jdbcClient;

    private final SpelAwareProxyProjectionFactory projections =
            new SpelAwareProxyProjectionFactory();

    @BeforeEach
    void migrate() {
        assertThat(gate.attempt()).isTrue();
    }

    @Test
    void clickHouse_tieGroupOnCreatedAt_pagesEveryRowExactlyOnceInTextOrder() {
        UUID admin = insertUser("ch_keyset_admin");
        insertReplicaRows(admin, TIED_IDS);

        List<UUID> seen = pageThrough(admin, false);

        assertThat(seen).containsExactlyElementsOf(expectedOrder());
    }

    @Test
    void alternatingStores_walkTheTieGroupWithoutLosingOrRepeatingARow() {
        UUID admin = insertUser("alternating_keyset_admin");
        insertReplicaRows(admin, TIED_IDS);
        insertPostgresRows(admin, TIED_IDS);

        List<UUID> alternating = pageThrough(admin, true);

        assertThat(alternating).containsExactlyElementsOf(expectedOrder());
        assertThat(alternating).isEqualTo(pageThrough(admin, false));
    }

    @Test
    void clickHouse_unmergedDuplicate_readsTheNewestVersionOnce() {
        UUID admin = insertUser("duplicate_keyset_admin");
        UUID id = UUID.randomUUID();
        // Both versions are inserted before any merge can run, so both parts exist when it reads.
        analyticsRepository.insert(replica(id, admin, null, "ban_user", "old", SHARED_INSTANT, 1));
        analyticsRepository.insert(replica(id, null, null, "ban_user", "new", SHARED_INSTANT, 2));
        analyticsRepository.insert(replica(id, admin, null, "ban_user", "old", SHARED_INSTANT, 1));

        List<AdminActionSummaryResponse> rows =
                analyticsRepository.findActions(
                        null,
                        null,
                        AdminActionType.BAN_USER,
                        SHARED_INSTANT,
                        SHARED_INSTANT.plusNanos(1_000),
                        null,
                        null,
                        10);

        List<AdminActionSummaryResponse> mine =
                rows.stream().filter(row -> row.id().equals(id)).toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).reason()).isEqualTo("new");
        assertThat(mine.get(0).adminId()).isNull();
    }

    @Test
    void clickHouse_filters_composeAndTheWindowIsHalfOpen() {
        UUID admin = insertUser("filter_keyset_admin");
        UUID targetA = insertUser("filter_keyset_target_a");
        UUID targetB = insertUser("filter_keyset_target_b");
        OffsetDateTime t1 = SHARED_INSTANT.plusDays(1);
        OffsetDateTime t2 = t1.plusSeconds(10);
        OffsetDateTime t3 = t2.plusSeconds(10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        analyticsRepository.insert(replica(first, admin, targetA, "ban_user", "a", t1, 1));
        analyticsRepository.insert(replica(second, admin, targetB, "warn_user", "b", t2, 1));
        analyticsRepository.insert(replica(third, admin, targetA, "ban_user", "c", t3, 1));

        assertThat(
                        ids(
                                analyticsRepository.findActions(
                                        admin, null, null, null, null, null, null, 10)))
                .containsExactly(third, second, first);
        assertThat(
                        ids(
                                analyticsRepository.findActions(
                                        admin,
                                        targetA,
                                        AdminActionType.BAN_USER,
                                        null,
                                        null,
                                        null,
                                        null,
                                        10)))
                .containsExactly(third, first);
        // From is included and to is not, so two adjacent windows partition the log.
        assertThat(ids(analyticsRepository.findActions(admin, null, null, t1, t3, null, null, 10)))
                .containsExactly(second, first);
        assertThat(
                        ids(
                                analyticsRepository.findActions(
                                        admin, null, null, t3, t3.plusSeconds(1), null, null, 10)))
                .containsExactly(third);
    }

    private List<UUID> expectedOrder() {
        // The text form of a UUID orders exactly as PostgreSQL orders its bytes.
        return TIED_IDS.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
    }

    /**
     * Pages through the tie group at {@link #PAGE_SIZE}, either on ClickHouse alone or with the
     * pages taken from ClickHouse, then PostgreSQL, then ClickHouse again, each continuing from the
     * last row of the page before it.
     */
    private List<UUID> pageThrough(UUID admin, boolean alternate) {
        List<UUID> seen = new ArrayList<>();
        OffsetDateTime cursorCreatedAt = null;
        UUID cursorId = null;
        for (int pageNumber = 0; pageNumber < 20; pageNumber++) {
            boolean useClickHouse = !alternate || pageNumber % 2 == 0;
            List<UUID> page = new ArrayList<>();
            OffsetDateTime lastCreatedAt = null;
            if (useClickHouse) {
                for (AdminActionSummaryResponse row :
                        analyticsRepository.findActions(
                                admin,
                                null,
                                null,
                                null,
                                null,
                                cursorCreatedAt,
                                cursorId,
                                PAGE_SIZE)) {
                    page.add(row.id());
                    lastCreatedAt = row.createdAt();
                }
            } else {
                for (AdminAction row :
                        adminActionRepository.findActions(
                                admin,
                                null,
                                null,
                                null,
                                null,
                                cursorCreatedAt,
                                cursorId,
                                PAGE_SIZE)) {
                    page.add(row.getId());
                    lastCreatedAt = row.getCreatedAt();
                }
            }
            if (page.isEmpty()) {
                return seen;
            }
            seen.addAll(page);
            cursorCreatedAt = lastCreatedAt;
            cursorId = page.get(page.size() - 1);
        }
        throw new AssertionError("paging did not terminate");
    }

    private static List<UUID> ids(List<AdminActionSummaryResponse> rows) {
        return rows.stream().map(AdminActionSummaryResponse::id).toList();
    }

    private void insertReplicaRows(UUID admin, List<UUID> ids) {
        for (UUID id : ids) {
            analyticsRepository.insert(
                    replica(id, admin, null, "ban_user", "tie", SHARED_INSTANT, 1));
        }
    }

    private void insertPostgresRows(UUID admin, List<UUID> ids) {
        for (UUID id : ids) {
            jdbcClient
                    .sql(
                            "INSERT INTO admin_actions(id, admin_id, action_type, created_at)"
                                    + " VALUES (:id, :adminId, 'ban_user', :createdAt)")
                    .param("id", id)
                    .param("adminId", admin)
                    .param("createdAt", SHARED_INSTANT)
                    .update();
        }
    }

    private AdminActionReplicaRow replica(
            UUID id,
            UUID adminId,
            UUID targetUserId,
            String actionType,
            String reason,
            OffsetDateTime createdAt,
            long rowVersion) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("adminId", adminId);
        values.put("actionType", actionType);
        values.put("targetUserId", targetUserId);
        values.put("reason", reason);
        values.put("metadata", "");
        values.put("createdAt", Instant.from(createdAt));
        values.put("rowVersion", rowVersion);
        return projections.createProjection(AdminActionReplicaRow.class, values);
    }

    private UUID insertUser(String prefix) {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 4);
        return jdbcClient
                .sql(
                        "INSERT INTO users(username, email, display_name) VALUES (:username,"
                                + " :email, :displayName) RETURNING id")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }
}
