package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class UserHashtagAffinityRepositoryIT {

    private static final long HALF_LIFE_SECONDS = 30L * 86_400L;

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private UserHashtagAffinityRepository affinityRepository;
    @Autowired private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    private int recompute() {
        return affinityRepository.recomputeWindow(now.minusDays(90), now, HALF_LIFE_SECONDS, now);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        "INSERT INTO users (username, email, display_name, role, status,"
                                + " is_private) VALUES (:u, :e, :d, 'user', 'active', FALSE) RETURNING"
                                + " id")
                .param("u", username)
                .param("e", username + "@affinity.test")
                .param("d", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertHashtag(String name, String status) {
        return jdbcClient
                .sql(
                        "INSERT INTO hashtags (name, status) VALUES (:n, CAST(:s AS hashtag_status))"
                                + " RETURNING id")
                .param("n", name)
                .param("s", status)
                .query(UUID.class)
                .single();
    }

    private UUID insertPost(UUID authorId, String caption, UUID... hashtagIds) {
        UUID postId =
                jdbcClient
                        .sql(
                                "INSERT INTO posts (user_id, caption, post_type, status) VALUES"
                                        + " (:u, :c, 'text', 'published') RETURNING id")
                        .param("u", authorId)
                        .param("c", caption)
                        .query(UUID.class)
                        .single();
        for (UUID hashtagId : hashtagIds) {
            jdbcClient
                    .sql("INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (:p, :h)")
                    .param("p", postId)
                    .param("h", hashtagId)
                    .update();
        }
        return postId;
    }

    private void insertEvent(UUID userId, String eventType, UUID postId, OffsetDateTime at) {
        jdbcClient
                .sql(
                        "INSERT INTO user_events (user_id, event_type, entity_type, entity_id,"
                                + " created_at) VALUES (:u, CAST(:t AS event_type), 'post', :e, :c)")
                .param("u", userId)
                .param("t", eventType)
                .param("e", postId)
                .param("c", at)
                .update();
    }

    private BigDecimal scoreOf(UUID userId, UUID hashtagId) {
        return jdbcClient
                .sql(
                        "SELECT score FROM user_hashtag_affinity WHERE user_id = :u AND hashtag_id"
                                + " = :h")
                .param("u", userId)
                .param("h", hashtagId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private long rowsFor(UUID userId) {
        return affinityRepository.countForUser(userId);
    }

    // The hard constraint on user_events: every read is bounded by created_at. An event older than
    // the window must not contribute, or the score stops describing the window it claims to.
    @Test
    void recomputeWindow_eventOlderThanWindow_doesNotContribute() {
        UUID user = insertUser("affinity_oldevent");
        UUID inside = insertHashtag("affinityinside", "active");
        UUID outside = insertHashtag("affinityoutside", "active");
        UUID recentPost = insertPost(user, "recent", inside);
        UUID ancientPost = insertPost(user, "ancient", outside);

        insertEvent(user, "post_like", recentPost, now.minusDays(1));
        insertEvent(user, "post_like", ancientPost, now.minusDays(120));

        recompute();

        assertThat(scoreOf(user, inside)).isNotNull();
        assertThat(scoreOf(user, outside)).isNull();
    }

    // Excluded at write time, not merely filtered on read: a suggestion surface that offered a
    // banned tag would produce a caption the post write path then refuses.
    @Test
    void recomputeWindow_bannedAndDeletedHashtags_neverGetRows() {
        UUID user = insertUser("affinity_banned");
        UUID active = insertHashtag("affinityok", "active");
        UUID banned = insertHashtag("affinitybanned", "banned");
        UUID deleted = insertHashtag("affinitygone", "deleted");
        UUID post = insertPost(user, "mixed", active, banned, deleted);

        insertEvent(user, "post_save", post, now.minusDays(2));

        recompute();

        assertThat(scoreOf(user, active)).isNotNull();
        assertThat(scoreOf(user, banned)).isNull();
        assertThat(scoreOf(user, deleted)).isNull();
    }

    // A user with three thousand events and one with thirty must produce comparable scores, or the
    // blend that reads this table ranks by activity volume instead of interest.
    @Test
    void recomputeWindow_normalisesPerUser_soVolumeDoesNotDominate() {
        UUID heavy = insertUser("affinity_heavy");
        UUID light = insertUser("affinity_light");
        UUID tag = insertHashtag("affinityshared", "active");
        UUID post = insertPost(heavy, "shared", tag);

        for (int i = 0; i < 50; i++) {
            insertEvent(heavy, "post_like", post, now.minusDays(3));
        }
        insertEvent(light, "post_like", post, now.minusDays(3));

        recompute();

        // Each user engaged with exactly one hashtag, so each holds the whole of their own share.
        assertThat(scoreOf(heavy, tag)).isEqualByComparingTo("1.0");
        assertThat(scoreOf(light, tag)).isEqualByComparingTo("1.0");
    }

    @Test
    void recomputeWindow_scoresForOneUser_sumToOne() {
        UUID user = insertUser("affinity_sum");
        UUID a = insertHashtag("affinitysuma", "active");
        UUID b = insertHashtag("affinitysumb", "active");
        UUID c = insertHashtag("affinitysumc", "active");
        insertEvent(user, "post_save", insertPost(user, "a", a), now.minusDays(1));
        insertEvent(user, "post_like", insertPost(user, "b", b), now.minusDays(5));
        insertEvent(user, "post_view", insertPost(user, "c", c), now.minusDays(9));

        recompute();

        BigDecimal total =
                jdbcClient
                        .sql("SELECT sum(score) FROM user_hashtag_affinity WHERE user_id = :u")
                        .param("u", user)
                        .query(BigDecimal.class)
                        .single();
        assertThat(total).isEqualByComparingTo("1.0");
    }

    // Weighted by intent, not counted equally: a save states more interest than a view.
    @Test
    void recomputeWindow_saveOutranksView_atEqualRecency() {
        UUID user = insertUser("affinity_intent");
        UUID saved = insertHashtag("affinitysaved", "active");
        UUID viewed = insertHashtag("affinityviewed", "active");
        insertEvent(user, "post_save", insertPost(user, "s", saved), now.minusDays(4));
        insertEvent(user, "post_view", insertPost(user, "v", viewed), now.minusDays(4));

        recompute();

        assertThat(scoreOf(user, saved)).isGreaterThan(scoreOf(user, viewed));
    }

    // Decay inside the window: the same action, more recently, must count for more.
    @Test
    void recomputeWindow_recentActionOutranksOlderIdenticalAction() {
        UUID user = insertUser("affinity_decay");
        UUID fresh = insertHashtag("affinityfresh", "active");
        UUID stale = insertHashtag("affinitystale", "active");
        insertEvent(user, "post_like", insertPost(user, "f", fresh), now.minusDays(1));
        insertEvent(user, "post_like", insertPost(user, "s", stale), now.minusDays(75));

        recompute();

        assertThat(scoreOf(user, fresh)).isGreaterThan(scoreOf(user, stale));
    }

    // A reversal cancels its own action exactly, so the pair leaves no residue behind.
    @Test
    void recomputeWindow_unlikeCancelsLike_dropsTheHashtagEntirely() {
        UUID user = insertUser("affinity_reversal");
        UUID kept = insertHashtag("affinitykept", "active");
        UUID reversed = insertHashtag("affinityreversed", "active");
        UUID reversedPost = insertPost(user, "r", reversed);
        insertEvent(user, "post_like", insertPost(user, "k", kept), now.minusDays(2));
        insertEvent(user, "post_like", reversedPost, now.minusDays(2));
        insertEvent(user, "post_unlike", reversedPost, now.minusDays(2));

        recompute();

        assertThat(scoreOf(user, kept)).isNotNull();
        assertThat(scoreOf(user, reversed)).isNull();
    }

    // Cold start. A user with no events in the window gets no rows, and the read path handles that
    // rather than the job fabricating one.
    @Test
    void recomputeWindow_userWithNoEvents_getsNoRows() {
        UUID silent = insertUser("affinity_silent");

        recompute();

        assertThat(rowsFor(silent)).isZero();
        assertThat(affinityRepository.findTopForUser(silent, 10)).isEmpty();
    }

    // No distributed scheduler lock exists, so a double run has to be harmless rather than
    // duplicative. The composite key plus ON CONFLICT DO UPDATE is what provides that.
    @Test
    void recomputeWindow_runTwice_rewritesRatherThanDuplicating() {
        UUID user = insertUser("affinity_twice");
        UUID tag = insertHashtag("affinitytwice", "active");
        insertEvent(user, "post_save", insertPost(user, "t", tag), now.minusDays(2));

        recompute();
        long afterFirst = rowsFor(user);
        BigDecimal scoreAfterFirst = scoreOf(user, tag);

        recompute();

        assertThat(rowsFor(user)).isEqualTo(afterFirst);
        assertThat(scoreOf(user, tag)).isEqualByComparingTo(scoreAfterFirst);
    }

    // A user who stopped engaging must lose their rows rather than keep a score frozen at whatever
    // it held when the job last saw them.
    @Test
    void deleteStale_removesRowsAnEarlierRunWrote() {
        UUID user = insertUser("affinity_stale");
        UUID tag = insertHashtag("affinitystaletag", "active");
        insertEvent(user, "post_save", insertPost(user, "st", tag), now.minusDays(2));
        recompute();
        assertThat(rowsFor(user)).isEqualTo(1);

        int removed = affinityRepository.deleteStale(now.plusMinutes(1));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(rowsFor(user)).isZero();
    }
}
