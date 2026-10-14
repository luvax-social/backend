package com.app.modules.social.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import com.app.modules.auth.service.TokenService;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class MuteControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "mute-controller-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://mute-controller.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "Mute IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        // The publisher stays off so every enqueued event remains a PENDING row to inspect.
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void putMute_postsOnly_returns200StoresRowAndEnqueuesMutedEvent() {
        Account viewer = account("mt_posts_v");
        Account target = account("mt_posts_t");

        ResponseEntity<String> response =
                put(target.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = data(response);
        assertThat(data.get("userId")).isEqualTo(target.id().toString());
        assertThat(data.get("mutePosts")).isEqualTo(true);
        assertThat(data.get("muteStories")).isEqualTo(false);
        assertThat(((Map<?, ?>) data.get("user")).get("id")).isEqualTo(target.id().toString());
        assertThat(((Map<?, ?>) data.get("user")).get("username")).isEqualTo("mt_posts_t");
        assertThat(muteRow(viewer.id(), target.id())).containsExactly(true, false);
        List<Map<String, Object>> events = events("user.muted.v1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .containsEntry("targetUserId", target.id().toString())
                .containsEntry("mutePosts", true)
                .containsEntry("muteStories", false)
                .hasSize(3);
    }

    @Test
    void putMute_changedScopes_updatesRowAndEnqueuesAnotherMutedEvent() {
        Account viewer = account("mt_change_v");
        Account target = account("mt_change_t");
        put(target.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());

        ResponseEntity<String> response =
                put(target.id(), Map.of("mutePosts", true, "muteStories", true), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(muteRow(viewer.id(), target.id())).containsExactly(true, true);
        assertThat(events("user.muted.v1")).hasSize(2);
    }

    @Test
    void putMute_sameScopesAgain_isIdempotentWithoutSecondEvent() {
        Account viewer = account("mt_same_v");
        Account target = account("mt_same_t");
        put(target.id(), Map.of("mutePosts", false, "muteStories", true), viewer.token());

        ResponseEntity<String> response =
                put(target.id(), Map.of("mutePosts", false, "muteStories", true), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events("user.muted.v1")).hasSize(1);
    }

    @Test
    void putMute_bothFalse_deletesRowAndEnqueuesUnmutedEvent() {
        Account viewer = account("mt_off_v");
        Account target = account("mt_off_t");
        put(target.id(), Map.of("mutePosts", true, "muteStories", true), viewer.token());
        jdbc.update("DELETE FROM outbox_events");

        ResponseEntity<String> response =
                put(target.id(), Map.of("mutePosts", false, "muteStories", false), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = data(response);
        assertThat(data.get("mutePosts")).isEqualTo(false);
        assertThat(data.get("muteStories")).isEqualTo(false);
        assertThat(muteRow(viewer.id(), target.id())).isEmpty();
        List<Map<String, Object>> events = events("user.unmuted.v1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("targetUserId", target.id().toString()).hasSize(1);
    }

    @Test
    void putMute_bothFalseWithoutARow_changesNothingAndEnqueuesNothing() {
        Account viewer = account("mt_off_none_v");
        Account target = account("mt_off_none_t");

        ResponseEntity<String> response =
                put(target.id(), Map.of("mutePosts", false, "muteStories", false), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events("user.unmuted.v1")).isEmpty();
    }

    @Test
    void putMute_self_returns400() {
        Account viewer = account("mt_self");

        ResponseEntity<String> response =
                put(viewer.id(), Map.of("mutePosts", true, "muteStories", true), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_mutes WHERE user_id = ?",
                                Integer.class,
                                viewer.id()))
                .isZero();
    }

    @Test
    void putMute_missingAndBlockedTargets_answerTheIdenticalNotFoundBody() {
        Account viewer = account("mt_stealth_v");
        Account blocker = account("mt_stealth_b");
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                blocker.id(),
                viewer.id());
        Map<String, Object> body = Map.of("mutePosts", true, "muteStories", true);

        ResponseEntity<String> missing = put(UUID.randomUUID(), body, viewer.token());
        ResponseEntity<String> blockedByTarget = put(blocker.id(), body, viewer.token());
        jdbc.update(
                "DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                blocker.id(),
                viewer.id());
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                viewer.id(),
                blocker.id());
        ResponseEntity<String> blockedByViewer = put(blocker.id(), body, viewer.token());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(blockedByTarget.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(blockedByViewer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(blockedByTarget)).isEqualTo(stableBody(missing));
        assertThat(stableBody(blockedByViewer)).isEqualTo(stableBody(missing));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM user_mutes WHERE user_id = ?",
                                Integer.class,
                                viewer.id()))
                .isZero();
    }

    @Test
    void putMute_softDeletedTarget_answersTheIdenticalNotFoundBodyAsAMissingOne() {
        Account viewer = account("mt_gone_v");
        Account gone = account("mt_gone_t");
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", gone.id());
        Map<String, Object> body = Map.of("mutePosts", true, "muteStories", true);

        ResponseEntity<String> missing = put(UUID.randomUUID(), body, viewer.token());
        ResponseEntity<String> softDeleted = put(gone.id(), body, viewer.token());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(softDeleted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(softDeleted)).isEqualTo(stableBody(missing));
        assertThat(muteRow(viewer.id(), gone.id())).isEmpty();
    }

    @Test
    void followingFeed_postsMuteRemovesTheAuthorAndUnmuteRestoresThem() {
        Account viewer = account("mt_feed_v");
        Account muted = account("mt_feed_m");
        Account storiesOnly = account("mt_feed_s");
        follow(viewer.id(), muted.id());
        follow(viewer.id(), storiesOnly.id());
        UUID mutedPost = publishedPost(muted.id());
        UUID storiesOnlyPost = publishedPost(storiesOnly.id());

        put(muted.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());
        put(storiesOnly.id(), Map.of("mutePosts", false, "muteStories", true), viewer.token());
        List<String> whileMuted = feedPostIds(viewer.token());
        send(HttpMethod.DELETE, mutePath(muted.id()), viewer.token());
        List<String> afterUnmute = feedPostIds(viewer.token());

        assertThat(whileMuted).containsExactly(storiesOnlyPost.toString());
        assertThat(afterUnmute)
                .containsExactlyInAnyOrder(mutedPost.toString(), storiesOnlyPost.toString());
    }

    @Test
    void putMute_missingScopeField_returns400() {
        Account viewer = account("mt_bad_v");
        Account target = account("mt_bad_t");

        ResponseEntity<String> response =
                put(target.id(), Map.of("mutePosts", true), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void putMute_withoutToken_returns401() {
        ResponseEntity<String> response =
                put(UUID.randomUUID(), Map.of("mutePosts", true, "muteStories", true), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deleteMute_rowExisted_returns204AndEnqueuesUnmutedEvent() {
        Account viewer = account("mt_del_v");
        Account target = account("mt_del_t");
        put(target.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());
        jdbc.update("DELETE FROM outbox_events");

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, mutePath(target.id()), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(muteRow(viewer.id(), target.id())).isEmpty();
        assertThat(events("user.unmuted.v1")).hasSize(1);
    }

    @Test
    void deleteMute_noRow_returns204WithoutEvent() {
        Account viewer = account("mt_del_none_v");

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, mutePath(UUID.randomUUID()), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(events("user.unmuted.v1")).isEmpty();
    }

    @Test
    void listMutes_returnsNewestFirstWithToggleFlagsSummariesAndPages() {
        Account viewer = account("mt_list_v");
        Account first = account("mt_list_1");
        Account second = account("mt_list_2");
        Account third = account("mt_list_3");
        put(first.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());
        put(second.id(), Map.of("mutePosts", false, "muteStories", true), viewer.token());
        put(third.id(), Map.of("mutePosts", true, "muteStories", true), viewer.token());

        ResponseEntity<String> page1 =
                send(HttpMethod.GET, "/api/v1/social/mutes?limit=2", viewer.token());

        assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data1 = data(page1);
        List<?> content1 = (List<?>) data1.get("content");
        assertThat(content1).hasSize(2);
        Map<?, ?> row0 = (Map<?, ?>) content1.get(0);
        Map<?, ?> row1 = (Map<?, ?>) content1.get(1);
        assertThat(row0.get("userId")).isEqualTo(third.id().toString());
        assertThat(row0.get("mutePosts")).isEqualTo(true);
        assertThat(row0.get("muteStories")).isEqualTo(true);
        assertThat(((Map<?, ?>) row0.get("user")).get("username")).isEqualTo("mt_list_3");
        assertThat(row1.get("userId")).isEqualTo(second.id().toString());
        assertThat(row1.get("mutePosts")).isEqualTo(false);
        assertThat(row1.get("muteStories")).isEqualTo(true);
        Map<?, ?> pageInfo = (Map<?, ?>) data1.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);

        ResponseEntity<String> page2 =
                send(
                        HttpMethod.GET,
                        "/api/v1/social/mutes?limit=2&cursor=" + pageInfo.get("endCursor"),
                        viewer.token());

        List<?> content2 = (List<?>) data(page2).get("content");
        assertThat(content2).hasSize(1);
        assertThat(((Map<?, ?>) content2.get(0)).get("userId")).isEqualTo(first.id().toString());
        assertThat(((Map<?, ?>) data(page2).get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    void listMutes_updatedMuteMovesToTheTop() {
        Account viewer = account("mt_move_v");
        Account older = account("mt_move_1");
        Account newer = account("mt_move_2");
        put(older.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());
        put(newer.id(), Map.of("mutePosts", true, "muteStories", false), viewer.token());
        put(older.id(), Map.of("mutePosts", true, "muteStories", true), viewer.token());

        List<?> content =
                (List<?>)
                        data(send(HttpMethod.GET, "/api/v1/social/mutes", viewer.token()))
                                .get("content");

        assertThat(((Map<?, ?>) content.get(0)).get("userId")).isEqualTo(older.id().toString());
    }

    @Test
    void listMutes_isScopedToTheCaller() {
        Account viewer = account("mt_scope_v");
        Account other = account("mt_scope_o");
        Account target = account("mt_scope_t");
        put(target.id(), Map.of("mutePosts", true, "muteStories", true), other.token());

        List<?> content =
                (List<?>)
                        data(send(HttpMethod.GET, "/api/v1/social/mutes", viewer.token()))
                                .get("content");

        assertThat(content).isEmpty();
    }

    @Test
    void listMutes_malformedCursorAndOversizedLimit_return400() {
        Account viewer = account("mt_cursor_v");

        assertThat(
                        send(HttpMethod.GET, "/api/v1/social/mutes?cursor=%21%21", viewer.token())
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        send(HttpMethod.GET, "/api/v1/social/mutes?limit=999", viewer.token())
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listMutes_withoutToken_returns401() {
        assertThat(send(HttpMethod.GET, "/api/v1/social/mutes", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void profileRead_mutingViewerSeesFlags_mutedAccountSeesNone() {
        Account muter = account("mt_prof_muter");
        Account muted = account("mt_prof_muted");
        put(muted.id(), Map.of("mutePosts", true, "muteStories", false), muter.token());

        Map<?, ?> seenByMuter = viewerState(muted.id(), muter.token());
        Map<?, ?> seenByMuted = viewerState(muter.id(), muted.token());

        assertThat(seenByMuter.get("isMutingPosts")).isEqualTo(true);
        assertThat(seenByMuter.get("isMutingStories")).isEqualTo(false);
        assertThat(seenByMuter.get("isShowingFewer")).isEqualTo(false);
        assertThat(seenByMuted.get("isMutingPosts")).isEqualTo(false);
        assertThat(seenByMuted.get("isMutingStories")).isEqualTo(false);
        assertThat(seenByMuted.get("isShowingFewer")).isEqualTo(false);
    }

    @Test
    void block_deletesMutesAndShowFewerRowsInBothDirections() {
        Account first = account("mt_block_1");
        Account second = account("mt_block_2");
        Account bystander = account("mt_block_3");
        Map<String, Object> both = Map.of("mutePosts", true, "muteStories", true);
        put(second.id(), both, first.token());
        put(first.id(), both, second.token());
        put(bystander.id(), both, first.token());
        send(HttpMethod.POST, "/api/v1/social/show-fewer/" + second.id(), first.token());
        send(HttpMethod.POST, "/api/v1/social/show-fewer/" + first.id(), second.token());
        send(HttpMethod.POST, "/api/v1/social/show-fewer/" + bystander.id(), first.token());

        ResponseEntity<String> block =
                send(HttpMethod.POST, "/api/v1/social/block/" + second.id(), first.token());

        assertThat(block.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(muteRow(first.id(), second.id())).isEmpty();
        assertThat(muteRow(second.id(), first.id())).isEmpty();
        assertThat(muteRow(first.id(), bystander.id())).containsExactly(true, true);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM account_show_fewer WHERE (user_id = ? AND"
                                        + " author_id = ?) OR (user_id = ? AND author_id = ?)",
                                Integer.class,
                                first.id(),
                                second.id(),
                                second.id(),
                                first.id()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM account_show_fewer WHERE user_id = ? AND"
                                        + " author_id = ?",
                                Integer.class,
                                first.id(),
                                bystander.id()))
                .isEqualTo(1);
    }

    private static String mutePath(UUID targetId) {
        return "/api/v1/social/mutes/" + targetId;
    }

    private ResponseEntity<String> put(UUID targetId, Map<String, Object> body, String token) {
        return exchange(HttpMethod.PUT, mutePath(targetId), body, token);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token) {
        return exchange(method, path, null, token);
    }

    private ResponseEntity<String> exchange(
            HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private void follow(UUID follower, UUID following) {
        jdbc.update(
                "INSERT INTO follows(follower_id, following_id, status)"
                        + " VALUES (?, ?, 'accepted')",
                follower,
                following);
    }

    private UUID publishedPost(UUID userId) {
        return jdbc.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    @SuppressWarnings("unchecked")
    private List<String> feedPostIds(String token) {
        ResponseEntity<String> response = send(HttpMethod.GET, "/api/v1/posts/feed", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data =
                (Map<String, Object>) JSON.readValue(response.getBody(), Map.class).get("data");
        return ((List<Map<String, Object>>) data.get("content"))
                .stream().map(post -> post.get("id").toString()).toList();
    }

    private Map<?, ?> data(ResponseEntity<String> response) {
        return (Map<?, ?>) JSON.readValue(response.getBody(), Map.class).get("data");
    }

    private Map<?, ?> viewerState(UUID profileId, String token) {
        ResponseEntity<String> response = send(HttpMethod.GET, "/api/v1/users/" + profileId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) data(response).get("viewerState");
    }

    // The envelope carries a timestamp that differs per response, so two answers are compared on
    // the whole body except that, which keeps a field added to the error later inside the check.
    private Map<?, ?> stableBody(ResponseEntity<String> response) {
        Map<?, ?> body =
                new java.util.LinkedHashMap<>(JSON.readValue(response.getBody(), Map.class));
        body.remove("timestamp");
        return body;
    }

    private List<Boolean> muteRow(UUID userId, UUID mutedId) {
        return jdbc
                .queryForList(
                        "SELECT mute_posts, mute_stories FROM user_mutes WHERE user_id = ? AND"
                                + " muted_user_id = ?",
                        userId,
                        mutedId)
                .stream()
                .flatMap(
                        row ->
                                java.util.stream.Stream.of(
                                        (Boolean) row.get("mute_posts"),
                                        (Boolean) row.get("mute_stories")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(String eventType) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type = ? ORDER BY"
                                + " created_at",
                        String.class,
                        eventType)
                .stream()
                .map(json -> (Map<String, Object>) JSON.readValue(json, Map.class).get("data"))
                .toList();
    }

    private record Account(UUID id, String token) {}

    @SuppressWarnings("unchecked")
    private Account account(String username) {
        String email = username + "_" + UUID.randomUUID().toString().substring(0, 6) + "@mt.test";
        String password = "Password1!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(
                "/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("username", username, "email", email, "password", password),
                        headers),
                Map.class);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        rest.getForEntity(
                "/api/v1/auth/verify-email?token="
                        + tokenService.createEmailVerificationToken(userId),
                Map.class);
        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        Map<String, String> data = (Map<String, String>) login.getBody().get("data");
        return new Account(userId, data.get("accessToken"));
    }
}
