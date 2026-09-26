package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the viewer-profile bound in {@code findAffinityCandidates} actually costs.
 *
 * <p>P7-BE-001. The bound was shipped with a Javadoc calling the discarded tail near-zero scores
 * that "contribute almost nothing", and nothing tested that claim. On the seeded data it is false:
 * the bound drops 49.4 percent of the viewer's score mass and leaves only 1 of 60 candidates in the
 * same position. The value stays at 32, because the fan-out it prevents is real, but the tradeoff
 * has to be visible to whoever changes it next.
 *
 * <p>These tests assert the rule rather than a rendering: that the bound is a ranking decision with
 * observable output effects, not a free truncation. A future change that made the bound signal-free
 * - or removed it - fails here rather than passing quietly.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AffinityProfileDepthIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final UserSuggestionRepository userSuggestionRepository;
    private final JdbcClient jdbcClient;

    AffinityProfileDepthIT(
            UserSuggestionRepository userSuggestionRepository, JdbcClient jdbcClient) {
        this.userSuggestionRepository = userSuggestionRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    /**
     * The tail is not noise. A candidate whose entire overlap sits below the cut outranks one above
     * it when the whole profile is matched, and disappears completely when it is not.
     */
    @Test
    void theBound_changesWhichAccountsAreCandidatesAtAll() {
        UUID viewer = user("depth_viewer");
        UUID head = user("depth_head_match");
        UUID tail = user("depth_tail_match");

        UUID h1 = hashtag("depth_h1");
        UUID h2 = hashtag("depth_h2");
        UUID h3 = hashtag("depth_h3");
        UUID h4 = hashtag("depth_h4");
        UUID h5 = hashtag("depth_h5");

        // A descending profile. Ranks 1-2 are the head, ranks 3-5 the tail a depth of 2 discards.
        affinity(viewer, h1, "0.50000000");
        affinity(viewer, h2, "0.40000000");
        affinity(viewer, h3, "0.30000000");
        affinity(viewer, h4, "0.30000000");
        affinity(viewer, h5, "0.30000000");

        // Overlap is SUM(LEAST(viewer, candidate)), so this account scores 0.20 on the head.
        affinity(head, h1, "0.20000000");

        // And this one scores 0.90 across the tail - more signal, all of it below the cut.
        affinity(tail, h3, "0.30000000");
        affinity(tail, h4, "0.30000000");
        affinity(tail, h5, "0.30000000");

        List<UUID> unbounded = userSuggestionRepository.findAffinityCandidates(viewer, 5, 60);
        List<UUID> bounded = userSuggestionRepository.findAffinityCandidates(viewer, 2, 60);

        // Matching the whole profile ranks the tail account first, on strictly more overlap.
        assertThat(unbounded).containsExactly(tail, head);
        // Bounding the profile removes it from the candidate set entirely.
        assertThat(bounded).containsExactly(head);
        assertThat(bounded).doesNotContain(tail);
    }

    /** The same rule stated as an ordering effect rather than a membership one. */
    @Test
    void theBound_reordersCandidatesItStillReturns() {
        UUID viewer = user("depth_viewer_order");
        UUID strongHead = user("depth_strong_head");
        UUID broadTail = user("depth_broad_tail");

        UUID h1 = hashtag("depth_o1");
        UUID h2 = hashtag("depth_o2");
        UUID h3 = hashtag("depth_o3");

        affinity(viewer, h1, "0.50000000");
        affinity(viewer, h2, "0.30000000");
        affinity(viewer, h3, "0.30000000");

        // Wins on the head alone: 0.25 against 0.05.
        affinity(strongHead, h1, "0.25000000");
        affinity(strongHead, h3, "0.01000000");

        // Wins once the tail counts: 0.05 + 0.30 = 0.35 against 0.26.
        affinity(broadTail, h1, "0.05000000");
        affinity(broadTail, h3, "0.30000000");

        assertThat(userSuggestionRepository.findAffinityCandidates(viewer, 3, 60))
                .containsExactly(broadTail, strongHead);
        assertThat(userSuggestionRepository.findAffinityCandidates(viewer, 1, 60))
                .containsExactly(strongHead, broadTail);
    }

    private UUID user(String username) {
        UUID id =
                jdbcClient
                        .sql(
                                "INSERT INTO users(username, email, display_name)"
                                        + " VALUES (:username, :email, :username) RETURNING id")
                        .param("username", username)
                        .param("email", username + "@example.com")
                        .query(UUID.class)
                        .single();
        jdbcClient.sql("INSERT INTO user_settings(user_id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private UUID hashtag(String name) {
        return jdbcClient
                .sql("INSERT INTO hashtags(name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private void affinity(UUID userId, UUID hashtagId, String score) {
        jdbcClient
                .sql(
                        "INSERT INTO user_hashtag_affinity(user_id, hashtag_id, score, weight,"
                                + " event_count, window_start, window_end)"
                                + " VALUES (:userId, :hashtagId, CAST(:score AS numeric), 1, 1,"
                                + " NOW() - INTERVAL '1 day', NOW())")
                .param("userId", userId)
                .param("hashtagId", hashtagId)
                .param("score", score)
                .update();
    }
}
