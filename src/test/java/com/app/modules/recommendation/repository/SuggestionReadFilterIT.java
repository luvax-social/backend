package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Every people-you-may-know exclusion, applied at read time against a real database.
 *
 * <p>Read time is the whole point. The precompute job runs on a twelve-hour cycle, so a filter that
 * only ran inside the job would keep offering an account the viewer followed, blocked or dismissed
 * this morning until tonight. Each test here writes a precomputed row and then changes the world
 * underneath it, which is exactly the case a job-time-only filter gets wrong.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SuggestionReadFilterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final UserSuggestionRepository userSuggestionRepository;
    private final SuggestionDismissalRepository suggestionDismissalRepository;
    private final JdbcClient jdbcClient;

    SuggestionReadFilterIT(
            UserSuggestionRepository userSuggestionRepository,
            SuggestionDismissalRepository suggestionDismissalRepository,
            JdbcClient jdbcClient) {
        this.userSuggestionRepository = userSuggestionRepository;
        this.suggestionDismissalRepository = suggestionDismissalRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void suggestion_withNothingAgainstIt_isReturned() {
        UUID viewer = user("viewer_plain");
        UUID candidate = user("candidate_plain");
        suggest(viewer, candidate, 1);

        assertThat(read(viewer)).containsExactly(candidate);
    }

    @Test
    void followedAfterThePrecompute_isFilteredOutOnTheNextRead() {
        // The case that makes read-time filtering necessary rather than merely tidy.
        UUID viewer = user("viewer_follows");
        UUID candidate = user("candidate_followed");
        suggest(viewer, candidate, 1);
        assertThat(read(viewer)).containsExactly(candidate);

        follow(viewer, candidate, "accepted");

        assertThat(read(viewer)).isEmpty();
    }

    @Test
    void pendingFollowRequest_isFilteredOutTheSameAsAnAcceptedFollow() {
        // Offering somebody you have already asked to follow is the most obviously broken case.
        UUID viewer = user("viewer_pending");
        UUID candidate = user("candidate_pending");
        suggest(viewer, candidate, 1);
        follow(viewer, candidate, "pending");

        assertThat(read(viewer)).isEmpty();
    }

    @Test
    void blockedInEitherDirection_isFilteredOut() {
        UUID viewer = user("viewer_blocks");
        UUID blockedByViewer = user("blocked_by_viewer");
        UUID blockerOfViewer = user("blocker_of_viewer");
        suggest(viewer, blockedByViewer, 1);
        suggest(viewer, blockerOfViewer, 2);

        block(viewer, blockedByViewer);
        block(blockerOfViewer, viewer);

        assertThat(read(viewer)).isEmpty();
    }

    @Test
    void bannedSuspendedAndDeactivated_areAllFilteredOut() {
        UUID viewer = user("viewer_statuses");
        UUID banned = user("candidate_banned");
        UUID suspended = user("candidate_suspended");
        UUID deactivated = user("candidate_deactivated");
        suggest(viewer, banned, 1);
        suggest(viewer, suspended, 2);
        suggest(viewer, deactivated, 3);

        setStatus(banned, "banned");
        setStatus(suspended, "suspended");
        setStatus(deactivated, "deactivated");

        assertThat(read(viewer)).isEmpty();
    }

    @Test
    void dismissedAccount_staysOutButRemainsVisibleToSearch() {
        UUID viewer = user("viewer_dismisses");
        UUID candidate = user("candidate_dismissed");
        suggest(viewer, candidate, 1);

        suggestionDismissalRepository.dismiss(viewer, candidate);

        assertThat(read(viewer)).isEmpty();
        // A dismissal is not a block. The account is untouched everywhere else, which is what
        // separates the two, so the row itself must still be an ordinary active account.
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT count(*) FROM users WHERE id = :id"
                                                + " AND status = 'active' AND deleted_at IS NULL")
                                .param("id", candidate)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void dismissal_isIdempotent() {
        UUID viewer = user("viewer_double_dismiss");
        UUID candidate = user("candidate_double_dismiss");
        suggest(viewer, candidate, 1);

        suggestionDismissalRepository.dismiss(viewer, candidate);
        suggestionDismissalRepository.dismiss(viewer, candidate);

        assertThat(read(viewer)).isEmpty();
    }

    @Test
    void optedOutAccount_disappearsFromEveryOtherViewersSuggestions() {
        UUID viewer = user("viewer_optout");
        UUID candidate = user("candidate_optout");
        suggest(viewer, candidate, 1);
        assertThat(read(viewer)).containsExactly(candidate);

        jdbcClient
                .sql("UPDATE user_settings SET suggestible = FALSE WHERE user_id = :id")
                .param("id", candidate)
                .update();

        assertThat(read(viewer)).isEmpty();
    }

    @Test
    void privateAccount_isStillSuggested() {
        // Following one produces a pending request, which is the normal contract. The account's own
        // control over being offered is the opt-out, not a blanket exclusion.
        UUID viewer = user("viewer_private");
        UUID candidate = user("candidate_private");
        jdbcClient
                .sql("UPDATE users SET is_private = TRUE WHERE id = :id")
                .param("id", candidate)
                .update();
        suggest(viewer, candidate, 1);

        assertThat(read(viewer)).containsExactly(candidate);
    }

    @Test
    void coldStart_returnsVerifiedAccountsMostFollowedFirst() {
        UUID viewer = user("viewer_cold");
        UUID quietVerified = user("verified_quiet");
        UUID popularVerified = user("verified_popular");
        UUID unverified = user("unverified_popular");

        verify(quietVerified, "music", 10);
        verify(popularVerified, "sport", 500);
        jdbcClient
                .sql("UPDATE users SET follower_count = 9000 WHERE id = :id")
                .param("id", unverified)
                .update();

        List<UUID> cold = userSuggestionRepository.findVerifiedColdStart(viewer, 10);

        assertThat(cold).containsExactly(popularVerified, quietVerified);
        assertThat(cold).doesNotContain(unverified);
    }

    @Test
    void coldStart_honoursDismissalAndOptOut() {
        UUID viewer = user("viewer_cold_filtered");
        UUID dismissed = user("verified_dismissed");
        UUID optedOut = user("verified_optout");
        verify(dismissed, "music", 100);
        verify(optedOut, "sport", 100);

        suggestionDismissalRepository.dismiss(viewer, dismissed);
        jdbcClient
                .sql("UPDATE user_settings SET suggestible = FALSE WHERE user_id = :id")
                .param("id", optedOut)
                .update();

        assertThat(userSuggestionRepository.findVerifiedColdStart(viewer, 10)).isEmpty();
    }

    @Test
    void grantingThenRevoking_movesTheAccountInAndOutOfTheColdStartList() {
        // users.is_verified and users.verified_category are trigger-maintained from
        // user_verifications and never written by application code, so this also asserts the
        // trigger.
        UUID viewer = user("viewer_trigger");
        UUID candidate = user("candidate_trigger");
        UUID grantId = verify(candidate, "science", 50);

        assertThat(userSuggestionRepository.findVerifiedColdStart(viewer, 10))
                .containsExactly(candidate);
        assertThat(verifiedCategoryOf(candidate)).isEqualTo("science");

        jdbcClient
                .sql(
                        "UPDATE user_verifications SET revoked_at = NOW(),"
                                + " revocation_actor = 'system' WHERE id = :id")
                .param("id", grantId)
                .update();

        assertThat(userSuggestionRepository.findVerifiedColdStart(viewer, 10)).isEmpty();
        assertThat(verifiedCategoryOf(candidate)).isNull();
    }

    @Test
    void suggestionsAreReturnedInPrecomputedRankOrder() {
        UUID viewer = user("viewer_order");
        UUID first = user("candidate_rank_one");
        UUID second = user("candidate_rank_two");
        UUID third = user("candidate_rank_three");
        suggest(viewer, third, 3);
        suggest(viewer, first, 1);
        suggest(viewer, second, 2);

        assertThat(read(viewer)).containsExactly(first, second, third);
    }

    private List<UUID> read(UUID viewer) {
        return userSuggestionRepository.findVisibleSuggestions(viewer, 20);
    }

    private String verifiedCategoryOf(UUID userId) {
        return jdbcClient
                .sql("SELECT verified_category FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private UUID user(String username) {
        UUID id =
                jdbcClient
                        .sql(
                                """
								INSERT INTO users(username, email, display_name)
								VALUES (:username, :email, :username)
								RETURNING id
								""")
                        .param("username", username)
                        .param("email", username + "@example.com")
                        .query(UUID.class)
                        .single();
        // Every account has exactly one user_settings row from creation, and the suggestion read
        // joins it inner rather than left for that reason.
        jdbcClient.sql("INSERT INTO user_settings(user_id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private UUID verify(UUID userId, String category, int followerCount) {
        jdbcClient
                .sql("UPDATE users SET follower_count = :count WHERE id = :id")
                .param("count", followerCount)
                .param("id", userId)
                .update();
        return jdbcClient
                .sql(
                        """
						INSERT INTO user_verifications(user_id, category_key)
						VALUES (:userId, :category)
						RETURNING id
						""")
                .param("userId", userId)
                .param("category", category)
                .query(UUID.class)
                .single();
    }

    private void suggest(UUID viewer, UUID candidate, int rank) {
        userSuggestionRepository.upsertSuggestion(
                viewer,
                candidate,
                (short) rank,
                new BigDecimal("0.01000000"),
                "graph",
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void follow(UUID follower, UUID following, String status) {
        jdbcClient
                .sql(
                        "INSERT INTO follows(follower_id, following_id, status)"
                                + " VALUES (:follower, :following, :status)")
                .param("follower", follower)
                .param("following", following)
                .param("status", status)
                .update();
    }

    private void block(UUID blocker, UUID blocked) {
        jdbcClient
                .sql("INSERT INTO blocks(blocker_id, blocked_id) VALUES (:blocker, :blocked)")
                .param("blocker", blocker)
                .param("blocked", blocked)
                .update();
    }

    private void setStatus(UUID userId, String status) {
        jdbcClient
                .sql("UPDATE users SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", userId)
                .update();
    }
}
