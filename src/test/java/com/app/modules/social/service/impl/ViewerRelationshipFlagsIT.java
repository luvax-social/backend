package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.service.PostService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.dto.response.PublicUserProfileResponse;
import com.app.modules.users.service.UserService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the viewer's mute and show-fewer choices reach the relationship flags on a profile read
 * and on the authors of a feed page, that they stay out of the other account's view, and that
 * resolving them costs one batched query however many accounts are asked about.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class ViewerRelationshipFlagsIT {

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
        r.add("JWT_SECRET", () -> "viewer-relationship-flags-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://viewer-relationship-flags.it.local");
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

    @MockitoBean private MailService mailService;

    @Autowired private SocialService socialService;
    @Autowired private UserService userService;
    @Autowired private PostService postService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM user_mutes");
        jdbcTemplate.update("DELETE FROM account_show_fewer");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void getUserProfile_viewerMutedPostsAndShowsFewer_flagsReflectTheViewersOwnChoices() {
        UUID viewer = insertUser("viewer");
        UUID target = insertUser("target");
        mute(viewer, target, true, false);
        showFewer(viewer, target);

        PublicUserProfileResponse response = userService.getUserProfile(viewer, target);

        assertThat(response.viewerState().isMutingPosts()).isTrue();
        assertThat(response.viewerState().isMutingStories()).isFalse();
        assertThat(response.viewerState().isShowingFewer()).isTrue();
    }

    @Test
    void getUserProfile_mutedAccountReadingTheMuter_carriesNoMuteOrShowFewerFlag() {
        UUID muter = insertUser("muter");
        UUID muted = insertUser("muted");
        mute(muter, muted, true, true);
        showFewer(muter, muted);

        PublicUserProfileResponse response = userService.getUserProfile(muted, muter);

        assertThat(response.viewerState().isMutingPosts()).isFalse();
        assertThat(response.viewerState().isMutingStories()).isFalse();
        assertThat(response.viewerState().isShowingFewer()).isFalse();
    }

    @Test
    void getUserProfile_selfView_allNewFlagsFalse() {
        UUID viewer = insertUser("viewer");

        PublicUserProfileResponse response = userService.getUserProfile(viewer, viewer);

        assertThat(response.viewerState()).isEqualTo(ViewerRelationshipResponse.NONE);
    }

    @Test
    void loadRelationships_authorsOfAFeedPage_carryPerAuthorFlagsNotUniformOnes() {
        UUID viewer = insertUser("viewer");
        UUID mutedAuthor = insertUser("muted_author");
        UUID fewerAuthor = insertUser("fewer_author");
        UUID plainAuthor = insertUser("plain_author");
        for (UUID author : List.of(mutedAuthor, fewerAuthor, plainAuthor)) {
            follow(viewer, author);
            insertPublishedPost(author);
        }
        mute(viewer, mutedAuthor, false, true);
        showFewer(viewer, fewerAuthor);

        List<UUID> feedAuthors =
                postService.getFeed(viewer, null, 10, FeedSurface.FOLLOWING).getContent().stream()
                        .map(FeedPostResponse::author)
                        .map(author -> author.id())
                        .toList();
        Map<UUID, ViewerRelationshipResponse> relationships =
                socialService.loadRelationships(viewer, feedAuthors);

        assertThat(feedAuthors).containsExactlyInAnyOrder(mutedAuthor, fewerAuthor, plainAuthor);
        assertThat(relationships.get(mutedAuthor).isMutingStories()).isTrue();
        assertThat(relationships.get(mutedAuthor).isMutingPosts()).isFalse();
        assertThat(relationships.get(mutedAuthor).isShowingFewer()).isFalse();
        assertThat(relationships.get(fewerAuthor).isShowingFewer()).isTrue();
        assertThat(relationships.get(fewerAuthor).isMutingPosts()).isFalse();
        assertThat(relationships.get(plainAuthor).isMutingPosts()).isFalse();
        assertThat(relationships.get(plainAuthor).isMutingStories()).isFalse();
        assertThat(relationships.get(plainAuthor).isShowingFewer()).isFalse();
        // The existing follow flag still resolves beside the new ones.
        assertThat(relationships.get(mutedAuthor).isFollowing()).isTrue();
    }

    @Test
    void loadRelationships_statementCountIsConstantAcrossBatchSizes() {
        UUID viewer = insertUser("viewer");
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID account = insertUser("account" + i);
            accounts.add(account);
            if (i % 2 == 0) {
                mute(viewer, account, true, i % 4 == 0);
            } else {
                showFewer(viewer, account);
            }
        }

        Statistics stats = statistics();
        socialService.loadRelationships(viewer, accounts.subList(0, 2));

        stats.clear();
        socialService.loadRelationships(viewer, accounts.subList(0, 2));
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.loadRelationships(viewer, accounts);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size12).isEqualTo(size2);
        // Follow edges, outgoing blocks and reported targets were already one batched query each;
        // the mute and show-fewer flags add exactly one more.
        assertThat(size12).isEqualTo(4);
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username);
    }

    private UUID insertPublishedPost(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    private void follow(UUID follower, UUID following) {
        jdbcTemplate.update(
                "INSERT INTO follows(follower_id, following_id, status) VALUES (?, ?, 'accepted')",
                follower,
                following);
    }

    private void mute(UUID userId, UUID mutedId, boolean posts, boolean stories) {
        jdbcTemplate.update(
                "INSERT INTO user_mutes(user_id, muted_user_id, mute_posts, mute_stories)"
                        + " VALUES (?, ?, ?, ?)",
                userId,
                mutedId,
                posts,
                stories);
    }

    private void showFewer(UUID userId, UUID authorId) {
        jdbcTemplate.update(
                "INSERT INTO account_show_fewer(user_id, author_id) VALUES (?, ?)",
                userId,
                authorId);
    }
}
