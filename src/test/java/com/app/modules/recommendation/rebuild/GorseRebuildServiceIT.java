package com.app.modules.recommendation.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.app.modules.recommendation.client.dto.GorseItemPage;
import com.app.modules.recommendation.client.dto.GorseUser;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

/**
 * The rebuild against real stores: PostgreSQL, ClickHouse and a Gorse 0.5.11 configured like
 * production. It proves the end state, not just the calls: drift planted in Gorse is gone, every
 * post is an item with the right hidden flag, feedback values equal the ClickHouse sums, feedback
 * for a post PostgreSQL does not have is skipped, a run cut off part way resumes from its
 * checkpoint to the same result, and a difference found at the end fails the run.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false",
            // One user per batch, so a run has two feedback batches to be cut off between.
            "app.recommendation.gorse-rebuild.user-batch-size=1",
            "app.recommendation.gorse-rebuild.requests-per-second=100"
        })
@Testcontainers
class GorseRebuildServiceIT {

    private static final String GORSE_API_KEY = "rebuild-it-key";

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();
    static final Network network = Network.newNetwork();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES)
                    .withNetwork(network)
                    .withNetworkAliases("gorse-postgres");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static GenericContainer<?> gorse;

    @BeforeAll
    static void startGorse() throws Exception {
        postgres.execInContainer(
                "psql",
                "-U",
                postgres.getUsername(),
                "-d",
                "postgres",
                "-c",
                "CREATE DATABASE gorse");
        String store =
                "postgres://"
                        + postgres.getUsername()
                        + ":"
                        + postgres.getPassword()
                        + "@gorse-postgres:5432/gorse?sslmode=disable";
        gorse =
                new GenericContainer<>(DockerImageName.parse("zhenghaoz/gorse-in-one:0.5.11"))
                        .withNetwork(network)
                        .withExposedPorts(8088)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(
                                        Path.of("gorse", "config", "config.toml").toAbsolutePath()),
                                "/etc/gorse/config.toml")
                        .withEnv("GORSE_DATA_STORE", store)
                        .withEnv("GORSE_CACHE_STORE", store)
                        .withEnv("GORSE_SERVER_API_KEY", GORSE_API_KEY)
                        .withEnv("GORSE_DASHBOARD_USER_NAME", "dashboard")
                        .withEnv("GORSE_DASHBOARD_PASSWORD", "dashboard")
                        .withCommand(
                                "-c",
                                "/etc/gorse/config.toml",
                                "--log-path",
                                "/var/lib/gorse/gorse.log",
                                "--cache-path",
                                "/var/lib/gorse/master")
                        .waitingFor(Wait.forLogMessage(".*start http server.*", 1));
        gorse.start();
    }

    @AfterAll
    static void stopGorse() {
        if (gorse != null) {
            gorse.stop();
        }
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add(
                "app.gorse.base-url",
                () -> "http://" + gorse.getHost() + ":" + gorse.getMappedPort(8088));
        r.add("app.gorse.api-key", () -> GORSE_API_KEY);
        r.add("app.gorse.read-timeout", () -> "PT20S");
        // The purger reaches the gorse database with these, on the container's own host and port.
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "gorse-rebuild-it-secret-that-is-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://gorse-rebuild.it.local");
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

    @Autowired private GorseRebuildService service;
    @Autowired private GorseRebuildRunRepository runs;
    @Autowired private UserEventAnalyticsRepository userEvents;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private JdbcTemplate jdbc;

    @MockitoSpyBean private GorseClient gorseClient;

    private UUID firstUser;
    private UUID secondUser;
    private UUID deletedUser;
    private UUID publishedWithTag;
    private UUID publishedPlain;
    private UUID removedPost;
    private UUID softDeletedPost;
    private final UUID unknownPost = UUID.randomUUID();

    @BeforeEach
    void seedPostgresAndClickHouse() throws SQLException {
        assertThat(gate.attempt()).isTrue();
        reset(gorseClient);
        firstUser = insertUser("rebuild_a", false);
        secondUser = insertUser("rebuild_b", false);
        deletedUser = insertUser("rebuild_gone", true);
        publishedWithTag = insertPost(firstUser, "published", false);
        publishedPlain = insertPost(firstUser, "published", false);
        removedPost = insertPost(secondUser, "removed", false);
        softDeletedPost = insertPost(secondUser, "published", true);
        UUID tag = UUID.randomUUID();
        jdbc.update("INSERT INTO hashtags (id, name) VALUES (?, 'rebuildmusic')", tag);
        jdbc.update(
                "INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (?, ?)",
                publishedWithTag,
                tag);

        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement()) {
            statement.execute("TRUNCATE TABLE luvax_analytics.user_events");
        }
        // like: 1.0 twice for one tuple, so the sum is 2.0, exactly what the live pipeline held.
        engagement(firstUser, UserEventType.POST_LIKE, publishedWithTag, "like", 1.0);
        engagement(firstUser, UserEventType.POST_LIKE, publishedWithTag, "like", 1.0);
        engagement(firstUser, UserEventType.POST_SAVE, publishedPlain, "save", 1.0);
        engagement(secondUser, UserEventType.POST_VIEW, publishedPlain, "read", 3.5);
        // A post PostgreSQL does not have, and an account it no longer serves.
        engagement(secondUser, UserEventType.POST_LIKE, unknownPost, "like", 1.0);
        engagement(deletedUser, UserEventType.POST_LIKE, publishedWithTag, "like", 1.0);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM post_hashtags");
        jdbc.update("DELETE FROM hashtags");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
    }

    private UUID insertUser(String prefix, boolean deleted) {
        UUID id = UUID.randomUUID();
        String username = prefix + "_" + id.toString().substring(0, 4);
        jdbc.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified,"
                        + " deleted_at) VALUES (?, ?, ?, 'user', 'active', FALSE, TRUE, ?)",
                id,
                username,
                username + "@test.local",
                deleted ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        return id;
    }

    private UUID insertPost(UUID author, String status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status, deleted_at)"
                        + " VALUES (?, ?, 'caption', 'text', CAST(? AS post_status), ?)",
                id,
                author,
                status,
                deleted ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        return id;
    }

    private void engagement(
            UUID user, UserEventType type, UUID post, String feedbackType, double value) {
        userEvents.insertEngagement(
                UUID.randomUUID(),
                user,
                type,
                "post",
                post,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
                feedbackType,
                value);
    }

    private GorseRebuildRun newRun() {
        return runs.create("it-" + UUID.randomUUID());
    }

    private Map<String, GorseItem> gorseCatalogue() {
        Map<String, GorseItem> items = new HashMap<>();
        String cursor = "";
        do {
            GorseItemPage page = gorseClient.listItems(cursor, 1000);
            page.items().forEach(item -> items.put(item.itemId(), item));
            cursor = page.cursor() == null ? "" : page.cursor();
        } while (!cursor.isEmpty());
        return items;
    }

    private void plantDriftInGorse() {
        gorseClient.upsertUsers(List.of(new GorseUser("stray-user", List.of(), "")));
        gorseClient.upsertItems(
                List.of(
                        new GorseItem(
                                "stray-item",
                                false,
                                List.of(),
                                List.of(),
                                OffsetDateTime.now(ZoneOffset.UTC),
                                null)));
        gorseClient.insertFeedback(
                List.of(
                        new GorseFeedback(
                                "like",
                                "stray-user",
                                "stray-item",
                                OffsetDateTime.now(ZoneOffset.UTC),
                                1.0)));
    }

    private Optional<GorseFeedback> heldFeedback(String type, UUID user, UUID post) {
        return gorseClient.getFeedback(type, user.toString(), post.toString());
    }

    private void assertGorseMatchesPostgresAndClickHouse() {
        Map<String, GorseItem> catalogue = gorseCatalogue();
        assertThat(catalogue.keySet())
                .containsExactlyInAnyOrder(
                        publishedWithTag.toString(),
                        publishedPlain.toString(),
                        removedPost.toString(),
                        softDeletedPost.toString());
        assertThat(catalogue.get(publishedWithTag.toString()).hidden()).isFalse();
        assertThat(catalogue.get(publishedWithTag.toString()).labels())
                .containsExactly("rebuildmusic");
        assertThat(catalogue.get(publishedPlain.toString()).hidden()).isFalse();
        assertThat(catalogue.get(removedPost.toString()).hidden()).isTrue();
        assertThat(catalogue.get(softDeletedPost.toString()).hidden()).isTrue();

        assertThat(heldFeedback("like", firstUser, publishedWithTag))
                .get()
                .extracting(GorseFeedback::value)
                .isEqualTo(2.0);
        assertThat(heldFeedback("save", firstUser, publishedPlain))
                .get()
                .extracting(GorseFeedback::value)
                .isEqualTo(1.0);
        assertThat(heldFeedback("read", secondUser, publishedPlain))
                .get()
                .extracting(GorseFeedback::value)
                .isEqualTo(3.5);
        // Never sent: an account PostgreSQL no longer serves, and a post it does not have.
        assertThat(heldFeedback("like", deletedUser, publishedWithTag)).isEmpty();
        assertThat(heldFeedback("like", secondUser, unknownPost)).isEmpty();
    }

    @Test
    void execute_driftedGorse_isReplacedByExactlyWhatPostgresAndClickHouseHold() {
        plantDriftInGorse();
        GorseRebuildRun run = newRun();

        service.execute(run);

        GorseRebuildRun finished = runs.findByToken(run.getToken()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
        assertThat(finished.getPhase()).isEqualTo(GorseRebuildPhase.DONE);
        assertThat(finished.getLastError()).isNull();
        assertThat(finished.getUsersSent()).isEqualTo(2);
        assertThat(finished.getItemsSent()).isEqualTo(4);
        assertThat(finished.getFeedbackSent()).isEqualTo(3);
        assertThat(finished.getFeedbackSkipped()).isEqualTo(1);
        assertThat(finished.getFinishedAt()).isNotNull();
        assertGorseMatchesPostgresAndClickHouse();
        assertThat(gorseCatalogue()).doesNotContainKey("stray-item");
        assertThat(gorseClient.getFeedback("like", "stray-user", "stray-item")).isEmpty();
    }

    @Test
    void execute_cutOffInFeedback_resumesFromTheCheckpointAndEndsIdentical() {
        plantDriftInGorse();
        AtomicInteger feedbackCalls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (feedbackCalls.incrementAndGet() == 2) {
                                throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
                            }
                            return invocation.callRealMethod();
                        })
                .when(gorseClient)
                .upsertFeedback(anyList());
        GorseRebuildRun run = newRun();

        service.execute(run);

        GorseRebuildRun interrupted = runs.findByToken(run.getToken()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(interrupted.getPhase()).isEqualTo(GorseRebuildPhase.FEEDBACK);
        assertThat(interrupted.getCheckpointId()).isNotNull();
        assertThat(interrupted.getUsersSent()).isEqualTo(2);
        assertThat(interrupted.getItemsSent()).isEqualTo(4);
        assertThat(interrupted.getFeedbackSent()).isLessThan(3);

        service.execute(runs.findByToken(run.getToken()).orElseThrow());

        GorseRebuildRun resumed = runs.findByToken(run.getToken()).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
        assertThat(resumed.getUsersSent()).isEqualTo(2);
        assertThat(resumed.getItemsSent()).isEqualTo(4);
        assertThat(resumed.getFeedbackSent()).isEqualTo(3);
        assertGorseMatchesPostgresAndClickHouse();
        assertThat(gorseCatalogue()).doesNotContainKey("stray-item");
    }

    @Test
    void execute_anItemFlippedBetweenItemsAndVerify_endsFailedVerificationNamingIt() {
        AtomicBoolean flipped = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            int pageSize = invocation.getArgument(1);
                            if (pageSize == 1000 && flipped.compareAndSet(false, true)) {
                                gorseClient.upsertItems(
                                        List.of(
                                                new GorseItem(
                                                        publishedPlain.toString(),
                                                        true,
                                                        List.of(),
                                                        List.of(),
                                                        OffsetDateTime.now(ZoneOffset.UTC),
                                                        null)));
                            }
                            return invocation.callRealMethod();
                        })
                .when(gorseClient)
                .listItems(any(), anyInt());
        GorseRebuildRun run = newRun();

        service.execute(run);

        GorseRebuildRun finished = runs.findByToken(run.getToken()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(GorseRebuildStatus.FAILED_VERIFICATION);
        assertThat(finished.getLastError()).contains("hiddenMismatch=1");
        assertThat(finished.getFinishedAt()).isNotNull();
    }
}
