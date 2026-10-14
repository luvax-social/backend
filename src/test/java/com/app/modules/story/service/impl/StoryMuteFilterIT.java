package com.app.modules.story.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.mail.service.MailService;
import com.app.modules.story.dto.response.StoryFeedItemResponse;
import com.app.modules.story.service.StoryService;
import com.app.testsupport.TestContainerImages;

/**
 * A viewer's story mutes shape the story tray and story discovery, and nothing else.
 *
 * <p>The tray subtracts the muted accounts from the authors it queries and discovery excludes them
 * inside its candidate query. A mute of posts alone never touches a story, another account's mute
 * never reaches this viewer, and the viewer's own tray entry cannot be muted away. Blocks keep
 * deciding visibility on their own: a mute neither replaces nor weakens them.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class StoryMuteFilterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "story-mute-filter-it-secret-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://story-mute.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private StoryService storyService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM story_views");
        jdbc.update("DELETE FROM stories");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM user_mutes");
        jdbc.update("DELETE FROM blocks");
        jdbc.update("DELETE FROM follows");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void getStoryFeed_authorWhoseStoriesAreMuted_leavesTheTrayAndOthersStay() {
        UUID viewer = user("tray_mute_viewer");
        UUID muted = user("tray_mute_muted");
        UUID kept = user("tray_mute_kept");
        follow(viewer, muted);
        follow(viewer, kept);
        story(muted);
        story(kept);
        mute(viewer, muted, false, true);

        assertThat(tray(viewer)).containsExactly(kept);
    }

    @Test
    void getStoryFeed_authorWhosePostsAreMutedWithStoriesOn_staysInTheTray() {
        UUID viewer = user("tray_postmute_viewer");
        UUID author = user("tray_postmute_author");
        follow(viewer, author);
        story(author);
        mute(viewer, author, true, false);

        assertThat(tray(viewer)).containsExactly(author);
    }

    @Test
    void getStoryFeed_authorMutedByAnotherViewer_staysInThisViewersTray() {
        UUID viewer = user("tray_other_viewer");
        UUID other = user("tray_other_other");
        UUID author = user("tray_other_author");
        follow(viewer, author);
        follow(other, author);
        story(author);
        mute(other, author, false, true);

        assertThat(tray(viewer)).containsExactly(author);
    }

    @Test
    void getStoryFeed_viewersOwnEntry_isKeptWhileEveryFollowedAuthorIsMuted() {
        UUID viewer = user("tray_self_viewer");
        UUID muted = user("tray_self_muted");
        follow(viewer, muted);
        story(viewer);
        story(muted);
        mute(viewer, muted, false, true);

        assertThat(tray(viewer)).containsExactly(viewer);
    }

    @Test
    void getStoryFeed_blockedAuthor_staysHiddenWhetherOrNotAMuteExists() {
        UUID viewer = user("tray_block_viewer");
        UUID blocked = user("tray_block_blocked");
        UUID blockedAndMuted = user("tray_block_both");
        follow(viewer, blocked);
        follow(viewer, blockedAndMuted);
        story(blocked);
        story(blockedAndMuted);
        block(viewer, blocked);
        block(viewer, blockedAndMuted);
        mute(viewer, blockedAndMuted, false, true);

        assertThat(tray(viewer)).isEmpty();

        jdbc.update("DELETE FROM user_mutes WHERE user_id = ?", viewer);

        assertThat(tray(viewer)).isEmpty();
    }

    @Test
    void discoverStories_authorWhoseStoriesAreMuted_isNotDiscovered() {
        UUID viewer = user("disc_mute_viewer");
        UUID muted = user("disc_mute_muted");
        UUID kept = user("disc_mute_kept");
        follow(viewer, muted);
        follow(viewer, kept);
        story(muted);
        story(kept);
        mute(viewer, muted, false, true);

        List<UUID> discovered =
                storyService.discoverStories(viewer, 10).stream()
                        .map(StoryFeedItemResponse::userId)
                        .toList();

        assertThat(discovered).containsExactly(kept);
    }

    @Test
    void discoverStories_authorWhosePostsAreMuted_isStillDiscovered() {
        UUID viewer = user("disc_postmute_viewer");
        UUID author = user("disc_postmute_author");
        follow(viewer, author);
        story(author);
        mute(viewer, author, true, false);

        List<UUID> discovered =
                storyService.discoverStories(viewer, 10).stream()
                        .map(StoryFeedItemResponse::userId)
                        .toList();

        assertThat(discovered).containsExactly(author);
    }

    private List<UUID> tray(UUID viewer) {
        return storyService.getStoryFeed(viewer).stream()
                .map(StoryFeedItemResponse::userId)
                .toList();
    }

    private UUID user(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified)"
                        + " VALUES (?, ?, ?, CAST('user' AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local");
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id);
        return id;
    }

    private void follow(UUID follower, UUID following) {
        jdbc.update(
                "INSERT INTO follows (follower_id, following_id, status)"
                        + " VALUES (?, ?, CAST('accepted' AS follow_status))",
                follower,
                following);
    }

    private void block(UUID blocker, UUID blocked) {
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }

    private void mute(UUID viewer, UUID muted, boolean mutePosts, boolean muteStories) {
        jdbc.update(
                "INSERT INTO user_mutes (user_id, muted_user_id, mute_posts, mute_stories)"
                        + " VALUES (?, ?, ?, ?)",
                viewer,
                muted,
                mutePosts,
                muteStories);
    }

    private void story(UUID author) {
        UUID media = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, 'https://cdn.example/s.jpg',"
                        + " 'image', 'image/jpeg', 1024)",
                media,
                author,
                "story/" + media);
        jdbc.update(
                "INSERT INTO stories (id, user_id, media_asset_id, expires_at)"
                        + " VALUES (?, ?, ?, NOW() + INTERVAL '12 hours')",
                UUID.randomUUID(),
                author,
                media);
    }
}
