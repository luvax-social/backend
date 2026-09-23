package com.app.modules.notification.controller;

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

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;

/**
 * The activity feed over HTTP, the way a client uses it: the page shape, filters, head, state, the
 * seen watermark, read state, deletion, hydration of every target kind including unavailable ones,
 * and the comment context endpoint the comment deep link loads.
 *
 * <p>Notifications are written through {@link NotificationService}, the path every producer uses,
 * so aggregation and actor membership are the production ones.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class NotificationControllerIT {

    private static final String TEST_JWT_SECRET = "notification-it-secret-32-chars-minimum-length!";
    private static final String BASE = "/api/v1/notifications";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> TEST_JWT_SECRET);
        r.add("JWT_ISSUER", () -> "https://notification-it.test.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Notification IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private NotificationService notificationService;
    @Autowired private JdbcTemplate jdbc;

    private UUID owner;
    private UUID anna;
    private UUID ben;

    @BeforeEach
    void setUp() {
        owner = user("owner", false);
        anna = user("anna", false);
        ben = user("ben", false);
    }

    @Test
    void list_withoutToken_isUnauthorized() {
        assertThat(rest.getForEntity(BASE, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void list_unknownFilterOrOversizedPage_isBadRequest() {
        assertThat(get(BASE + "?filter=likes", owner).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(BASE + "?limit=101", owner).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void list_likesOnOnePost_areOneGroupWithTheNewestActorFirstAndAThumbnail() {
        UUID post = post(owner, "published", true);
        like(anna, post);
        like(ben, post);

        Map<String, Object> page = data(get(BASE, owner));
        List<Map<String, Object>> content = content(page);

        assertThat(content).hasSize(1);
        Map<String, Object> item = content.get(0);
        assertThat(item.get("type")).isEqualTo("like_post");
        assertThat(item.get("category")).isEqualTo("like");
        assertThat(item.get("actorCount")).isEqualTo(2);
        assertThat(actorIds(item)).containsExactly(ben.toString(), anna.toString());
        assertThat(item.get("isRead")).isEqualTo(false);
        assertThat(item.get("isNew")).isEqualTo(true);
        assertThat(map(item, "target").get("kind")).isEqualTo("post");
        assertThat(map(item, "target").get("available")).isEqualTo(true);
        assertThat(map(map(item, "preview"), "media").get("url")).isEqualTo("https://cdn/" + post);
        assertThat(map(page, "head").get("id")).isEqualTo(item.get("id"));
    }

    @Test
    void list_pendingRequestLeadsTheHeadAndThePinnedEntryButIsNeverARow() {
        UUID comment = comment(post(owner, "published", false), anna, null, "nice");
        create(anna, NotificationType.COMMENT_POST, "comment", comment, null);
        follow(ben, owner, "pending");
        UUID request = create(ben, NotificationType.FOLLOW_REQUEST, null, null, null);

        Map<String, Object> page = data(get(BASE, owner));
        Map<String, Object> follows = data(get(BASE + "?filter=follows", owner));
        Map<String, Object> state = data(get(BASE + "/state", owner));

        assertThat(ids(content(page))).doesNotContain(request.toString());
        assertThat(content(follows)).isEmpty();
        assertThat(map(page, "head").get("id")).isEqualTo(request.toString());
        assertThat(map(state, "unseen").get("count")).isEqualTo(2);
        assertThat(map(state, "followRequests").get("count")).isEqualTo(1);
        assertThat((List<?>) map(state, "followRequests").get("recent")).hasSize(1);
    }

    @Test
    void seen_advancingToTheHead_clearsTheBadge_andRejectsAnotherAccountsRow() {
        UUID post = post(owner, "published", true);
        like(anna, post);
        Map<String, Object> head = map(data(get(BASE, owner)), "head");

        ResponseEntity<Map> advanced = send(HttpMethod.POST, BASE + "/seen", owner, head);
        UUID foreign = create(anna, NotificationType.FOLLOW, null, null, null, ben);
        ResponseEntity<Map> forbidden =
                send(
                        HttpMethod.POST,
                        BASE + "/seen",
                        owner,
                        Map.of("activityAt", head.get("activityAt"), "id", foreign.toString()));
        ResponseEntity<Map> invalid =
                send(HttpMethod.POST, BASE + "/seen", owner, Map.of("id", foreign.toString()));

        assertThat(advanced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(map(data(advanced), "unseen").get("count")).isEqualTo(0);
        assertThat(map(data(advanced), "seen").get("id")).isEqualTo(head.get("id"));
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void readUnreadAndDelete_areIdempotentAndOwnerOnly() {
        UUID row = create(anna, NotificationType.FOLLOW, null, null, null);
        String path = BASE + "/" + row + "/read";

        Map<String, Object> read = data(send(HttpMethod.PUT, path, owner, null));
        Map<String, Object> readAgain = data(send(HttpMethod.PUT, path, owner, null));
        ResponseEntity<Map> unreadResponse = send(HttpMethod.DELETE, path, owner, null);
        assertThat(unreadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> unread = data(unreadResponse);
        ResponseEntity<Map> othersRead = send(HttpMethod.PUT, path, anna, null);
        ResponseEntity<Map> deleted = send(HttpMethod.DELETE, BASE + "/" + row, owner, null);
        ResponseEntity<Map> deletedAgain = send(HttpMethod.DELETE, BASE + "/" + row, owner, null);
        ResponseEntity<Map> othersDelete = send(HttpMethod.DELETE, BASE + "/" + row, anna, null);
        ResponseEntity<Map> readAfterDelete = send(HttpMethod.PUT, path, owner, null);

        assertThat(read.get("readAt")).isNotNull();
        assertThat(readAgain.get("readAt")).isEqualTo(read.get("readAt"));
        assertThat(unread.get("readAt")).isNull();
        assertThat(othersRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedAgain.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(othersDelete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(readAfterDelete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(content(data(get(BASE, owner)))).isEmpty();
    }

    @Test
    void readAll_marksOnlyRowsAtOrBelowTheRenderedBound() {
        UUID older = notice();
        UUID newer = notice();
        Map<String, Object> olderItem = item(owner, older);

        Map<String, Object> result =
                data(
                        send(
                                HttpMethod.PATCH,
                                BASE + "/read-all",
                                owner,
                                Map.of(
                                        "upTo",
                                        Map.of(
                                                "activityAt", olderItem.get("activityAt"),
                                                "id", older.toString()))));

        assertThat(result.get("updated")).isEqualTo(1);
        assertThat(item(owner, older).get("isRead")).isEqualTo(true);
        assertThat(item(owner, newer).get("isRead")).isEqualTo(false);
        assertThat(ids(content(data(get(BASE + "?filter=unread", owner)))))
                .containsExactly(newer.toString());
    }

    @Test
    void list_unavailableTargets_areReturnedMutedWithoutAPreview() {
        UUID deletedPost = post(owner, "published", true);
        like(anna, deletedPost);
        jdbc.update("UPDATE posts SET deleted_at = now() WHERE id = ?", deletedPost);

        UUID livePost = post(owner, "published", true);
        UUID removedComment = comment(livePost, anna, null, "rude");
        create(anna, NotificationType.COMMENT_POST, "comment", removedComment, livePost);
        jdbc.update("UPDATE comments SET admin_removed_at = now() WHERE id = ?", removedComment);

        UUID expiredStory = story(owner);
        create(anna, NotificationType.STORY_VIEW, "story", expiredStory, null);
        jdbc.update(
                "UPDATE stories SET expires_at = now() - interval '1 minute' WHERE id = ?",
                expiredStory);

        UUID privateAuthor = user("private", true);
        UUID privatePost = post(privateAuthor, "published", true);
        UUID mention = comment(privatePost, privateAuthor, null, "hey @owner");
        create(privateAuthor, NotificationType.MENTION_COMMENT, "comment", mention, privatePost);

        List<Map<String, Object>> content = content(data(get(BASE, owner)));

        assertThat(content).hasSize(4);
        assertThat(content)
                .allSatisfy(
                        item -> {
                            assertThat(map(item, "target").get("available")).isEqualTo(false);
                            assertThat(item.get("preview")).isNull();
                        });
    }

    @Test
    void list_actorsBlockedOrInactive_hideTheirRowsAndLeaveGroupsCountingOnlyVisibleActors() {
        UUID post = post(owner, "published", true);
        like(anna, post);
        like(ben, post);
        UUID blocker = user("blocker", false);
        create(blocker, NotificationType.FOLLOW, null, null, null);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", owner, ben);
        jdbc.update("UPDATE users SET status = 'banned' WHERE id = ?", blocker);

        List<Map<String, Object>> content = content(data(get(BASE, owner)));

        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("actorCount")).isEqualTo(1);
        assertThat(actorIds(content.get(0))).containsExactly(anna.toString());
    }

    @Test
    void list_moderationNotice_showsTheDecisionAndTheAppealRoute_withoutActors() {
        UUID comment = comment(post(owner, "published", false), owner, null, "my words");
        UUID audit =
                jdbc.queryForObject(
                        "INSERT INTO admin_actions (action_type, target_user_id,"
                                + " target_entity_type, target_entity_id, reason) VALUES"
                                + " ('remove_comment', ?, 'comment', ?, 'harassment') RETURNING id",
                        UUID.class,
                        owner,
                        comment);
        notificationService.create(
                NotificationDraft.systemNotice(
                        owner,
                        NotificationType.COMMENT_REMOVED,
                        "admin_action",
                        audit,
                        null,
                        "harassment",
                        audit));

        Map<String, Object> item = content(data(get(BASE + "?filter=system", owner))).get(0);

        assertThat((List<?>) item.get("actors")).isEmpty();
        assertThat(map(item, "target").get("kind")).isEqualTo("moderation");
        assertThat(map(item, "moderation").get("actionType")).isEqualTo("remove_comment");
        assertThat(map(item, "moderation").get("reason")).isEqualTo("harassment");
        assertThat(map(item, "moderation").get("affectedSnippet")).isEqualTo("my words");
        assertThat(map(item, "moderation").get("appealActionId")).isEqualTo(audit.toString());
    }

    @Test
    void list_followNotification_carriesTheRelationshipForFollowBack() {
        follow(anna, owner, "accepted");
        create(anna, NotificationType.FOLLOW, null, null, null);

        Map<String, Object> item = content(data(get(BASE + "?filter=follows", owner))).get(0);

        assertThat(map(item, "target").get("kind")).isEqualTo("user");
        assertThat(map(item, "target").get("userId")).isEqualTo(anna.toString());
        assertThat(map(item, "relationship").get("isFollowing")).isEqualTo(false);
    }

    @Test
    void commentContext_returnsTheThreadTopFirst_andHidesAThreadWithARemovedAncestor() {
        UUID post = post(owner, "published", false);
        UUID top = comment(post, owner, null, "top");
        UUID reply = comment(post, anna, top, "reply");
        UUID deep = comment(post, owner, reply, "deep");

        ResponseEntity<Map> thread = get("/api/v1/comments/" + deep + "/context", anna);
        jdbc.update("UPDATE comments SET admin_removed_at = now() WHERE id = ?", reply);
        ResponseEntity<Map> hidden = get("/api/v1/comments/" + deep + "/context", anna);

        assertThat(thread.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(thread).get("postId")).isEqualTo(post.toString());
        assertThat(ids(listOf(data(thread), "thread")))
                .containsExactly(top.toString(), reply.toString(), deep.toString());
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Map<String, Object> item(UUID viewer, UUID id) {
        return content(data(get(BASE + "?limit=50", viewer))).stream()
                .filter(row -> id.toString().equals(row.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private UUID notice() {
        notificationService.create(
                NotificationDraft.systemNotice(
                        owner,
                        NotificationType.WARNING,
                        "warning",
                        UUID.randomUUID(),
                        null,
                        null,
                        null));
        return jdbc.queryForObject(
                "SELECT id FROM notifications WHERE recipient_id = ? ORDER BY activity_at DESC"
                        + " LIMIT 1",
                UUID.class,
                owner);
    }

    private void like(UUID liker, UUID post) {
        jdbc.update("INSERT INTO post_likes (user_id, post_id) VALUES (?, ?)", liker, post);
        notificationService.create(liker, owner, NotificationType.LIKE_POST, "post", post, post);
    }

    private UUID create(
            UUID actor, NotificationType type, String entityType, UUID entityId, UUID postId) {
        return create(actor, type, entityType, entityId, postId, owner);
    }

    private UUID create(
            UUID actor,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId,
            UUID recipient) {
        notificationService.create(actor, recipient, type, entityType, entityId, postId);
        return jdbc.queryForObject(
                "SELECT id FROM notifications WHERE recipient_id = ? ORDER BY activity_at DESC"
                        + " LIMIT 1",
                UUID.class,
                recipient);
    }

    private UUID post(UUID author, String status, boolean withMedia) {
        UUID post =
                jdbc.queryForObject(
                        "INSERT INTO posts (user_id, status) VALUES (?, CAST(? AS post_status))"
                                + " RETURNING id",
                        UUID.class,
                        author,
                        status);
        if (withMedia) {
            UUID asset = asset(author, "https://cdn/" + post);
            jdbc.update(
                    "INSERT INTO post_media (post_id, media_asset_id, position) VALUES (?, ?, 0)",
                    post,
                    asset);
        }
        return post;
    }

    private UUID story(UUID author) {
        return jdbc.queryForObject(
                "INSERT INTO stories (user_id, media_asset_id) VALUES (?, ?) RETURNING id",
                UUID.class,
                author,
                asset(author, "https://cdn/story-" + UUID.randomUUID()));
    }

    private UUID asset(UUID author, String url) {
        return jdbc.queryForObject(
                "INSERT INTO media_assets (user_id, storage_key, cdn_url, media_type, mime_type,"
                        + " file_size, width, height) VALUES (?, ?, ?, 'image', 'image/jpeg', 10,"
                        + " 1080, 1350) RETURNING id",
                UUID.class,
                author,
                "k/" + UUID.randomUUID(),
                url);
    }

    private UUID comment(UUID post, UUID author, UUID parent, String content) {
        Integer depth =
                parent == null
                        ? 0
                        : jdbc.queryForObject(
                                        "SELECT depth FROM comments WHERE id = ?",
                                        Integer.class,
                                        parent)
                                + 1;
        UUID root =
                parent == null
                        ? null
                        : jdbc.queryForObject(
                                "SELECT COALESCE(root_id, id) FROM comments WHERE id = ?",
                                UUID.class,
                                parent);
        return jdbc.queryForObject(
                "INSERT INTO comments (post_id, user_id, parent_id, root_id, depth, content)"
                        + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                UUID.class,
                post,
                author,
                parent,
                root,
                depth,
                content);
    }

    private void follow(UUID follower, UUID following, String status) {
        jdbc.update(
                "INSERT INTO follows (follower_id, following_id, status)"
                        + " VALUES (?, ?, CAST(? AS follow_status))",
                follower,
                following,
                status);
    }

    private UUID user(String prefix, boolean isPrivate) {
        String name = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO users (username, email, is_private) VALUES (?, ?, ?)"
                                + " RETURNING id",
                        UUID.class,
                        name,
                        name + "@test.local",
                        isPrivate);
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id);
        return id;
    }

    private ResponseEntity<Map> get(String path, UUID user) {
        return send(HttpMethod.GET, path, user, null);
    }

    private ResponseEntity<Map> send(HttpMethod method, String path, UUID user, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(user, "USER", 0));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.get(key);
    }

    private static List<Map<String, Object>> content(Map<String, Object> page) {
        return listOf(page, "content");
    }

    private static List<Object> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> actorIds(Map<String, Object> item) {
        return ((List<Map<String, Object>>) item.get("actors"))
                .stream().map(actor -> actor.get("id")).toList();
    }
}
