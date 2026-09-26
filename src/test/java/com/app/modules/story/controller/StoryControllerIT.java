package com.app.modules.story.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.story.service.impl.StoryCleanupScheduler;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class StoryControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "story-controller-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://story.it.local");
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

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StoryCleanupScheduler cleanupScheduler;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM story_views");
        jdbcTemplate.update("DELETE FROM stories");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM oauth_accounts");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void storyLifecycle_createFeedGetListDelete() {
        TestUser author = registerUser("story_author");
        TestUser follower = registerUser("story_follower");
        insertFollow(follower.id(), author.id(), "accepted");

        UUID storyId = createStory(author, "hello");

        // Owner reads: view count populated, seen flag absent.
        ResponseEntity<Map> ownerGet = getWithAuth("/api/v1/stories/" + storyId, author);
        assertThat(ownerGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> ownerData = (Map<?, ?>) ownerGet.getBody().get("data");
        assertThat(ownerData.get("viewCount")).isEqualTo(0);
        assertThat(ownerData.get("seen")).isNull();

        // Follower feed shows the author's tray with hasUnseen true.
        ResponseEntity<Map> feed = getWithAuth("/api/v1/stories/feed", follower);
        assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> trayItems = feedItems(feed);
        Map<?, ?> authorTray =
                trayItems.stream()
                        .filter(i -> i.get("userId").equals(author.id().toString()))
                        .findFirst()
                        .orElseThrow();
        assertThat(authorTray.get("hasUnseen")).isEqualTo(true);

        // Follower lists the author's stories directly.
        ResponseEntity<Map> listResponse =
                getWithAuth("/api/v1/stories/user/" + author.id(), follower);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listItems(listResponse)).hasSize(1);

        // Follower records a view; outbox row written, story_views row inserted, counter bumped.
        ResponseEntity<Map> viewed = recordView(follower, storyId);
        assertThat(viewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> viewData = (Map<?, ?>) viewed.getBody().get("data");
        assertThat(viewData.get("viewed")).isEqualTo(true);
        assertThat(viewData.get("viewCount")).isEqualTo(1);
        assertThat(storyViewCount(storyId)).isEqualTo(1);
        assertThat(outboxEventCount("story.viewed.v1")).isEqualTo(1);

        // Owner lists viewers; the follower shows up.
        ResponseEntity<Map> viewers = getWithAuth("/api/v1/stories/" + storyId + "/views", author);
        assertThat(viewers.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> viewerContent = viewerContentOf(viewers);
        assertThat(viewerContent).hasSize(1);
        assertThat(viewerContent.get(0).get("viewerId")).isEqualTo(follower.id().toString());

        // Owner deletes; subsequent reads 404.
        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/stories/" + storyId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> afterDelete = getWithAuth("/api/v1/stories/" + storyId, author);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(afterDelete.getBody().get("code")).isEqualTo("STORY_NOT_FOUND");
    }

    @Test
    void recordView_owner_insertsNoRowAndNoOutboxEvent() {
        TestUser author = registerUser("view_owner_self");
        UUID storyId = createStory(author, "own story");

        ResponseEntity<Map> response = recordView(author, storyId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("viewed")).isEqualTo(false);
        assertThat(storyViewRowCount(storyId)).isZero();
        assertThat(outboxEventCount("story.viewed.v1")).isZero();
    }

    @Test
    void recordView_twice_singleRowAndViewCountOne() {
        TestUser author = registerUser("view_twice_owner");
        TestUser viewer = registerUser("view_twice_viewer");
        UUID storyId = createStory(author, "viewed twice");

        ResponseEntity<Map> first = recordView(viewer, storyId);
        ResponseEntity<Map> second = recordView(viewer, storyId);

        assertThat(((Map<?, ?>) first.getBody().get("data")).get("viewed")).isEqualTo(true);
        assertThat(((Map<?, ?>) second.getBody().get("data")).get("viewed")).isEqualTo(false);
        assertThat(storyViewRowCount(storyId)).isEqualTo(1);
        assertThat(storyViewCount(storyId)).isEqualTo(1);
        assertThat(outboxEventCount("story.viewed.v1")).isEqualTo(1);
    }

    @Test
    void recordView_expiredStory_returnsNotFound() {
        TestUser author = registerUser("view_expiry_owner");
        TestUser viewer = registerUser("view_expiry_viewer");
        UUID storyId = createStory(author, "expiring");
        jdbcTemplate.update(
                "UPDATE stories SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?", storyId);

        ResponseEntity<Map> response = recordView(viewer, storyId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("STORY_NOT_FOUND");
    }

    @Test
    void listViewers_nonOwner_returnsForbidden() {
        TestUser author = registerUser("viewers_owner");
        TestUser stranger = registerUser("viewers_stranger");
        UUID storyId = createStory(author, "private viewers");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/stories/" + storyId + "/views", stranger);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("STORY_FORBIDDEN");
    }

    @Test
    void cleanupScheduler_purgesOnlySoftDeletedExpiredStories() {
        TestUser owner = registerUser("cleanup_owner");
        UUID deletedAndExpired = createStory(owner, "deleted and expired");
        UUID expiredOnly = createStory(owner, "expired only");
        UUID deletedOnly = createStory(owner, "deleted only");

        jdbcTemplate.update(
                "UPDATE stories SET deleted_at = NOW(), expires_at = NOW() - INTERVAL '1 hour'"
                        + " WHERE id = ?",
                deletedAndExpired);
        jdbcTemplate.update(
                "UPDATE stories SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?",
                expiredOnly);
        jdbcTemplate.update("UPDATE stories SET deleted_at = NOW() WHERE id = ?", deletedOnly);

        cleanupScheduler.purgeSoftDeletedExpiredStories();

        assertThat(storyExistsIgnoringFilters(deletedAndExpired)).isFalse();
        assertThat(storyExistsIgnoringFilters(expiredOnly)).isTrue();
        assertThat(storyExistsIgnoringFilters(deletedOnly)).isTrue();
    }

    @Test
    void createStory_mediaNotOwned_returnsForbidden() {
        TestUser author = registerUser("wrong_owner");
        TestUser other = registerUser("asset_owner");
        UUID mediaId = insertMediaAsset(other.id(), "image");

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/stories",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("mediaId", mediaId.toString()), authHeaders(author)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("STORY_FORBIDDEN");
    }

    @Test
    void getStoryById_expired_returnsNotFound() {
        TestUser author = registerUser("expiry_author");
        UUID storyId = createStory(author, "will expire");
        jdbcTemplate.update(
                "UPDATE stories SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?", storyId);

        ResponseEntity<Map> response = getWithAuth("/api/v1/stories/" + storyId, author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("STORY_NOT_FOUND");
    }

    @Test
    void listUserStories_privateAccountWithoutFollow_returnsForbiddenThenOkAfterFollow() {
        TestUser owner = registerUser("priv_story_owner");
        TestUser stranger = registerUser("priv_story_stranger");
        createStory(owner, "private story");
        jdbcTemplate.update("UPDATE users SET is_private = TRUE WHERE id = ?", owner.id());

        ResponseEntity<Map> forbidden = getWithAuth("/api/v1/stories/user/" + owner.id(), stranger);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().get("code")).isEqualTo("STORY_FORBIDDEN");

        insertFollow(stranger.id(), owner.id(), "accepted");
        ResponseEntity<Map> allowed = getWithAuth("/api/v1/stories/user/" + owner.id(), stranger);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listItems(allowed)).hasSize(1);
    }

    @Test
    void listUserStories_blocked_returnsNotFound() {
        // Stealth block model: a blocked target must be indistinguishable from a nonexistent one,
        // so this is NOT_FOUND rather than a status that confirms the block relationship exists.
        TestUser owner = registerUser("block_story_owner");
        TestUser viewer = registerUser("block_story_viewer");
        createStory(owner, "blocked story");
        jdbcTemplate.update(
                "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)",
                owner.id(),
                viewer.id());

        ResponseEntity<Map> response = getWithAuth("/api/v1/stories/user/" + owner.id(), viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void listViewers_limitZero_returns400() {
        TestUser viewer = registerUser("viewers_limitzero_viewer");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/stories/" + UUID.randomUUID() + "/views?limit=0", viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void deleteStory_nonOwner_returnsForbidden() {
        TestUser owner = registerUser("del_story_owner");
        TestUser stranger = registerUser("del_story_stranger");
        UUID storyId = createStory(owner, "not yours");

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/stories/" + storyId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(stranger)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("STORY_FORBIDDEN");
    }

    private ResponseEntity<Map> recordView(TestUser viewer, UUID storyId) {
        return rest.exchange(
                "/api/v1/stories/" + storyId + "/views",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(viewer)),
                Map.class);
    }

    private int storyViewRowCount(UUID storyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM story_views WHERE story_id = ?", Integer.class, storyId);
    }

    private int storyViewCount(UUID storyId) {
        return jdbcTemplate.queryForObject(
                "SELECT view_count FROM stories WHERE id = ?", Integer.class, storyId);
    }

    private int outboxEventCount(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ?",
                Integer.class,
                eventType);
    }

    private boolean storyExistsIgnoringFilters(UUID storyId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stories WHERE id = ?", Integer.class, storyId);
        return count != null && count > 0;
    }

    private UUID createStory(TestUser author, String caption) {
        UUID mediaId = insertMediaAsset(author.id(), "image");
        Map<String, Object> body = new HashMap<>();
        body.put("mediaId", mediaId.toString());
        body.put("caption", caption);
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/stories",
                        HttpMethod.POST,
                        new HttpEntity<>(body, authHeaders(author)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private UUID insertMediaAsset(UUID ownerId, String mediaType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, ?, CAST(? AS media_type), ?, ?)",
                id,
                ownerId,
                "test/" + id,
                "https://cdn.test/" + id,
                mediaType,
                "image".equals(mediaType) ? "image/jpeg" : "video/mp4",
                1024L);
        return id;
    }

    private void insertFollow(UUID followerId, UUID followingId, String status) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id, status)"
                        + " VALUES (?, ?, CAST(? AS follow_status))",
                followerId,
                followingId,
                status);
    }

    private TestUser registerUser(String username) {
        String email = username + "@test.local";
        String password = "S3cur3P@ssword!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());

        Map<String, Object> registerPayload =
                Map.of(
                        "username", username,
                        "email", email,
                        "password", password,
                        "displayName", username);
        ResponseEntity<Map> reg =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>(registerPayload, headers),
                        Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        jdbcTemplate.update(
                "UPDATE user_credentials SET email_verified = TRUE WHERE user_id = ?", id);

        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        return new TestUser(id, (String) data.get("accessToken"));
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> getWithAuth(String url, TestUser user) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> feedItems(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (List<Map<?, ?>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> listItems(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (List<Map<?, ?>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> viewerContentOf(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<?, ?>>) data.get("content");
    }

    private static String uniqueIp() {
        Random rand = new Random();
        return "10." + rand.nextInt(256) + "." + rand.nextInt(256) + "." + (1 + rand.nextInt(254));
    }
}
