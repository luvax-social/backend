package com.app.modules.hashtag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.hashtag.entity.HashtagTrending;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the audit's F-2.2-a concern is disproved for a genuine same-key collision while locking in
 * the real defects D5 identified: {@code windowStart} never repeated in production (so the reported
 * unique-violation could not fire), but the snapshot grew without bound and its DELETE-then-insert
 * was dead code on the scheduled path. {@link HashtagTrendingServiceImpl#runTrendingJob()} now
 * truncates {@code windowStart} to the hour, and {@link
 * HashtagTrendingServiceImpl#snapshotTrending} writes via {@code INSERT ... ON CONFLICT ... DO
 * UPDATE} so a repeated key merges instead of throwing.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.hashtag.trending.job-delay=PT24H",
            "app.hashtag.trending.job-initial-delay=PT24H"
        })
@Testcontainers
class HashtagTrendingSnapshotIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "hashtag-trending-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://hashtag-trending-it.test.local");
        r.add("JWT_AUDIENCE", () -> "hashtag-trending-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Hashtag Trending IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @MockitoBean private MailService mailService;

    @Autowired private HashtagTrendingServiceImpl trendingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JdbcClient jdbcClient;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM hashtag_trending");
        jdbcTemplate.update("DELETE FROM post_hashtags");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM hashtags");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void runTrendingJob_twiceInSameHour_writesOneSnapshot() {
        UUID author = insertUser("trend_job_" + suffix());
        UUID hashtagId = insertHashtag("job_" + suffix());
        UUID postId = insertPost(author, OffsetDateTime.now(ZoneOffset.UTC));
        linkPostHashtag(postId, hashtagId);

        trendingService.runTrendingJob();
        trendingService.runTrendingJob();

        List<OffsetDateTime> periodStarts =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT period_start FROM hashtag_trending WHERE hashtag_id = ?",
                        OffsetDateTime.class,
                        hashtagId);

        assertThat(periodStarts)
                .as("both runs within the same hour must share one period_start")
                .hasSize(1);
        OffsetDateTime periodStart = periodStarts.get(0);
        assertThat(periodStart)
                .as("period_start must be truncated to the hour boundary")
                .isEqualTo(periodStart.truncatedTo(ChronoUnit.HOURS));
    }

    @Test
    void snapshotTrending_sameWindowTwice_isIdempotent() {
        OffsetDateTime windowStart = fixedWindowStart();
        OffsetDateTime windowEnd = windowStart.plusHours(1);
        UUID author = insertUser("trend_idem_" + suffix());
        UUID hashtagId = insertHashtag("idem_" + suffix());
        UUID postId = insertPost(author, windowStart.plusMinutes(5));
        linkPostHashtag(postId, hashtagId);

        List<HashtagTrending> first = trendingService.snapshotTrending(windowStart, windowEnd);
        List<HashtagTrending> second = trendingService.snapshotTrending(windowStart, windowEnd);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getPostCount()).isEqualTo(first.get(0).getPostCount());
        assertThat(second.get(0).getRank()).isEqualTo(first.get(0).getRank());

        Integer rowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM hashtag_trending"
                                + " WHERE hashtag_id = ? AND period_start = ?",
                        Integer.class,
                        hashtagId,
                        windowStart);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void snapshotTrending_hashtagFallsOutOfTopN_rowIsRemoved() {
        OffsetDateTime windowStart = fixedWindowStart();
        OffsetDateTime windowEnd = windowStart.plusHours(1);
        UUID author = insertUser("trend_fall_" + suffix());
        UUID hashtagA = insertHashtag("fall_a_" + suffix());
        UUID hashtagB = insertHashtag("fall_b_" + suffix());
        UUID postA = insertPost(author, windowStart.plusMinutes(5));
        UUID postB = insertPost(author, windowStart.plusMinutes(5));
        linkPostHashtag(postA, hashtagA);
        linkPostHashtag(postB, hashtagB);

        List<HashtagTrending> first = trendingService.snapshotTrending(windowStart, windowEnd);
        assertThat(first)
                .extracting(r -> r.getId().getHashtagId())
                .containsExactlyInAnyOrder(hashtagA, hashtagB);

        // hashtagB's only post falls out of the window, so hashtagB must fall out of the snapshot.
        jdbcTemplate.update("UPDATE posts SET deleted_at = now() WHERE id = ?", postB);

        List<HashtagTrending> second = trendingService.snapshotTrending(windowStart, windowEnd);
        assertThat(second).extracting(r -> r.getId().getHashtagId()).containsExactly(hashtagA);

        Integer remaining =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM hashtag_trending"
                                + " WHERE hashtag_id = ? AND period_start = ?",
                        Integer.class,
                        hashtagB,
                        windowStart);
        assertThat(remaining)
                .as("the DELETE must remove the stale row now that period_start repeats")
                .isZero();
    }

    @Test
    void snapshotTrending_concurrentSameWindow_bothSucceed() throws Exception {
        OffsetDateTime windowStart = fixedWindowStart();
        OffsetDateTime windowEnd = windowStart.plusHours(1);
        UUID author = insertUser("trend_conc_" + suffix());
        UUID hashtagId = insertHashtag("conc_" + suffix());
        UUID postId = insertPost(author, windowStart.plusMinutes(5));
        linkPostHashtag(postId, hashtagId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<Void> task =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        trendingService.snapshotTrending(windowStart, windowEnd);
                        return null;
                    };
            Future<Void> first = pool.submit(task);
            Future<Void> second = pool.submit(task);

            assertThatCode(
                            () -> {
                                first.get(15, TimeUnit.SECONDS);
                                second.get(15, TimeUnit.SECONDS);
                            })
                    .as("two instances racing the same window must merge, not throw")
                    .doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }

        Integer rowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM hashtag_trending"
                                + " WHERE hashtag_id = ? AND period_start = ?",
                        Integer.class,
                        hashtagId,
                        windowStart);
        assertThat(rowCount)
                .as("the final state must be a single consistent snapshot, not a duplicate")
                .isEqualTo(1);
    }

    private static OffsetDateTime fixedWindowStart() {
        return OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :displayName)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertHashtag(String name) {
        return jdbcClient
                .sql("INSERT INTO hashtags(name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private UUID insertPost(UUID userId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO posts(user_id, post_type, status, created_at)
						VALUES (:userId, 'image', 'published', :createdAt) RETURNING id
						""")
                .param("userId", userId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }

    private void linkPostHashtag(UUID postId, UUID hashtagId) {
        jdbcClient
                .sql("INSERT INTO post_hashtags(post_id, hashtag_id) VALUES (:postId, :hashtagId)")
                .param("postId", postId)
                .param("hashtagId", hashtagId)
                .update();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
