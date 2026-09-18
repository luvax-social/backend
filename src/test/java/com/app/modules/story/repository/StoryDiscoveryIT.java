package com.app.modules.story.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * Discovery widens who a story can be found by; it must not widen who may see one.
 *
 * <p>Every exclusion here mirrors a rule {@code listUserStories} already enforces for a direct
 * profile visit. The query is native, so it bypasses the {@code @SQLRestriction} on {@code Story}
 * that covers both tombstones on every JPQL read, and has to restate them itself.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class StoryDiscoveryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private StoryRepository storyRepository;
    @Autowired private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    private List<UUID> discover(UUID viewer) {
        return storyRepository.findDiscoverableAuthors(
                viewer, OffsetDateTime.now(ZoneOffset.UTC), 8);
    }

    @Test
    void findDiscoverableAuthors_suggestedPublicAccount_isDiscoverable() {
        UUID viewer = insertUser("discviewer", false);
        UUID author = insertUser("discauthor", false);
        suggest(viewer, author);
        insertStory(author, false, false, false);

        assertThat(discover(viewer)).containsExactly(author);
    }

    @Test
    void findDiscoverableAuthors_privateAccount_isExcluded() {
        UUID viewer = insertUser("discprivviewer", false);
        UUID author = insertUser("discprivauthor", true);
        suggest(viewer, author);
        insertStory(author, false, false, false);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_blockedEitherDirection_isExcluded() {
        UUID viewer = insertUser("discblockviewer", false);
        UUID blockedByViewer = insertUser("discblocked", false);
        UUID blockingViewer = insertUser("discblocker", false);
        suggest(viewer, blockedByViewer);
        suggest(viewer, blockingViewer);
        insertStory(blockedByViewer, false, false, false);
        insertStory(blockingViewer, false, false, false);
        block(viewer, blockedByViewer);
        block(blockingViewer, viewer);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_alreadyFollowed_isExcludedBecauseTheTrayHasIt() {
        UUID viewer = insertUser("discfollowviewer", false);
        UUID author = insertUser("discfollowauthor", false);
        suggest(viewer, author);
        insertStory(author, false, false, false);
        follow(viewer, author);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_dismissedSuggestion_isExcluded() {
        UUID viewer = insertUser("discdismissviewer", false);
        UUID author = insertUser("discdismissauthor", false);
        suggest(viewer, author);
        insertStory(author, false, false, false);
        jdbcClient
                .sql(
                        "INSERT INTO suggestion_dismissals (user_id, dismissed_id) VALUES (:viewer,"
                                + " :author)")
                .param("viewer", viewer)
                .param("author", author)
                .update();

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_accountOptedOutOfBeingSuggested_isExcluded() {
        UUID viewer = insertUser("discoptoutviewer", false);
        UUID author = insertUser("discoptoutauthor", false);
        suggest(viewer, author);
        insertStory(author, false, false, false);
        jdbcClient
                .sql("UPDATE user_settings SET suggestible = FALSE WHERE user_id = :id")
                .param("id", author)
                .update();

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_expiredStory_isExcluded() {
        UUID viewer = insertUser("discexpviewer", false);
        UUID author = insertUser("discexpauthor", false);
        suggest(viewer, author);
        insertStory(author, true, false, false);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_ownerDeletedStory_isExcluded() {
        UUID viewer = insertUser("discdelviewer", false);
        UUID author = insertUser("discdelauthor", false);
        suggest(viewer, author);
        insertStory(author, false, true, false);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_administrativelyRemovedStory_isExcluded() {
        UUID viewer = insertUser("discadmviewer", false);
        UUID author = insertUser("discadmauthor", false);
        suggest(viewer, author);
        insertStory(author, false, false, true);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_suspendedAccount_isExcluded() {
        UUID viewer = insertUser("discsuspviewer", false);
        UUID author = insertUser("discsuspauthor", false);
        suggest(viewer, author);
        insertStory(author, false, false, false);
        jdbcClient
                .sql("UPDATE users SET status = 'suspended' WHERE id = :id")
                .param("id", author)
                .update();

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_authorWithNoActiveStory_isExcluded() {
        UUID viewer = insertUser("discnostoryviewer", false);
        UUID author = insertUser("discnostoryauthor", false);
        suggest(viewer, author);

        assertThat(discover(viewer)).isEmpty();
    }

    @Test
    void findDiscoverableAuthors_severalAuthors_answersInSuggestionRankOrder() {
        UUID viewer = insertUser("discrankviewer", false);
        UUID first = insertUser("discrankfirst", false);
        UUID second = insertUser("discranksecond", false);
        suggestAtRank(viewer, second, 1);
        suggestAtRank(viewer, first, 2);
        insertStory(first, false, false, false);
        insertStory(second, false, false, false);

        assertThat(discover(viewer)).containsExactly(second, first);
    }

    private UUID insertUser(String username, boolean isPrivate) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO users (id, username, email, is_private) VALUES (:id, :username,"
                                + " :email, :isPrivate)")
                .param("id", id)
                .param("username", username)
                .param("email", username + "@example.com")
                .param("isPrivate", isPrivate)
                .update();
        jdbcClient.sql("INSERT INTO user_settings (user_id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private void suggest(UUID viewer, UUID suggested) {
        suggestAtRank(viewer, suggested, 1);
    }

    private void suggestAtRank(UUID viewer, UUID suggested, int rank) {
        jdbcClient
                .sql(
                        "INSERT INTO user_suggestions (user_id, suggested_id, rank, score, sources)"
                                + " VALUES (:viewer, :suggested, :rank, 1.0, 'graph')")
                .param("viewer", viewer)
                .param("suggested", suggested)
                .param("rank", rank)
                .update();
    }

    private void block(UUID blocker, UUID blocked) {
        jdbcClient
                .sql("INSERT INTO blocks (blocker_id, blocked_id) VALUES (:blocker, :blocked)")
                .param("blocker", blocker)
                .param("blocked", blocked)
                .update();
    }

    private void follow(UUID follower, UUID following) {
        jdbcClient
                .sql(
                        "INSERT INTO follows (follower_id, following_id, status) VALUES (:follower,"
                                + " :following, 'accepted')")
                .param("follower", follower)
                .param("following", following)
                .update();
    }

    private void insertStory(
            UUID author, boolean expired, boolean ownerDeleted, boolean adminRemoved) {
        UUID media = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                                + " mime_type, file_size) VALUES (:id, :userId, :key,"
                                + " 'https://cdn.example/s.jpg', 'image', 'image/jpeg', 1024)")
                .param("id", media)
                .param("userId", author)
                .param("key", "story/" + media)
                .update();
        jdbcClient
                .sql(
                        "INSERT INTO stories (id, user_id, media_asset_id, expires_at, deleted_at,"
                                + " admin_removed_at) VALUES (gen_random_uuid(), :userId, :mediaId,"
                                + " NOW() + CAST(:offset AS INTERVAL), :deletedAt, :adminRemovedAt)")
                .param("userId", author)
                .param("mediaId", media)
                .param("offset", expired ? "-1 hour" : "12 hours")
                .param("deletedAt", ownerDeleted ? OffsetDateTime.now(ZoneOffset.UTC) : null)
                .param("adminRemovedAt", adminRemoved ? OffsetDateTime.now(ZoneOffset.UTC) : null)
                .update();
    }
}
