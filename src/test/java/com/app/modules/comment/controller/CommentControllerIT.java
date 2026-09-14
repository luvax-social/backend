package com.app.modules.comment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.mail.service.MailService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.comment.consumer.enabled=false",
            "app.comment.live.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class CommentControllerIT {

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
        r.add("JWT_SECRET", () -> "comment-controller-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://comment.it.local");
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

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comment_likes");
        jdbcTemplate.update("DELETE FROM comment_write_idempotency");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM posts");
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
    void commentLifecycle_createReplyEditLikeDelete() {
        TestUser author = registerUser("c_author");
        TestUser commenter = registerUser("c_commenter");
        UUID postId = createImagePost(author, "nice post");

        UUID commentId = createComment(commenter, postId, null, "great shot", null);
        assertThat(topLevelCount(postId)).isEqualTo(1);

        UUID replyId = createComment(author, postId, commentId, "thank you", null);
        ResponseEntity<Map> replies =
                getWithAuth("/api/v1/comments/" + commentId + "/replies", author);
        assertThat(contentOf(replies)).hasSize(1);
        assertThat(contentOf(replies).get(0).get("id")).isEqualTo(replyId.toString());

        ResponseEntity<Map> edited =
                rest.exchange(
                        "/api/v1/comments/" + commentId,
                        HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("content", "edited body"), authHeaders(commenter)),
                        Map.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) edited.getBody().get("data")).get("content"))
                .isEqualTo("edited body");

        ResponseEntity<Map> liked =
                rest.exchange(
                        "/api/v1/comments/" + commentId + "/like",
                        HttpMethod.POST,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);
        assertThat(liked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(likeCount(commentId)).isEqualTo(1);

        ResponseEntity<Map> unliked =
                rest.exchange(
                        "/api/v1/comments/" + commentId + "/like",
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);
        assertThat(unliked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(likeCount(commentId)).isZero();

        // Deleting the root soft-deletes the whole subtree (root + reply).
        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/comments/" + commentId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(commenter)),
                        Map.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(topLevelCount(postId)).isZero();
    }

    @Test
    void createComment_success_returnsNonNullTimestamps() {
        TestUser author = registerUser("ts_author");
        TestUser commenter = registerUser("ts_commenter");
        UUID postId = createImagePost(author, "timestamp probe post");

        Map<String, Object> body = new HashMap<>();
        body.put("postId", postId.toString());
        body.put("content", "timestamp probe comment");
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postId + "/comments",
                        HttpMethod.POST,
                        new HttpEntity<>(body, authHeaders(commenter)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("createdAt")).as("createdAt on the create response").isNotNull();
        assertThat(data.get("updatedAt")).as("updatedAt on the create response").isNotNull();
    }

    @Test
    void createComment_idempotencyKey_replaysOriginalResponse() {
        TestUser author = registerUser("idem_author");
        TestUser commenter = registerUser("idem_commenter");
        UUID postId = createImagePost(author, "idem post");

        UUID first = createComment(commenter, postId, null, "once", "key-123");
        UUID second = createComment(commenter, postId, null, "once", "key-123");

        assertThat(second).isEqualTo(first);
        assertThat(topLevelCount(postId)).isEqualTo(1);
    }

    @Test
    void createComment_privatePostWithoutFollow_returnsForbidden() {
        TestUser owner = registerUser("priv_owner");
        TestUser stranger = registerUser("priv_stranger");
        UUID postId = createImagePost(owner, "private");
        jdbcTemplate.update("UPDATE users SET is_private = TRUE WHERE id = ?", owner.id());

        Map<String, Object> body = new HashMap<>();
        body.put("postId", postId.toString());
        body.put("content", "let me in");
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postId + "/comments",
                        HttpMethod.POST,
                        new HttpEntity<>(body, authHeaders(stranger)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("POST_COMMENTING_RESTRICTED");
    }

    @Test
    void createComment_idempotencyKeyReusedWithDifferentPayload_returnsConflict() {
        TestUser author = registerUser("conf_author");
        TestUser commenter = registerUser("conf_commenter");
        UUID postId = createImagePost(author, "conflict post");

        createComment(commenter, postId, null, "original", "conf-key");

        Map<String, Object> body = new HashMap<>();
        body.put("postId", postId.toString());
        body.put("content", "changed payload");
        HttpHeaders headers = authHeaders(commenter);
        headers.set("Idempotency-Key", "conf-key");
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postId + "/comments",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("COMMENT_IDEMPOTENCY_CONFLICT");
        assertThat(topLevelCount(postId)).isEqualTo(1);
    }

    @Test
    void listComments_firstPage_marksThePinnedTopCommentAndLeavesTheBodyUnpinned() {
        TestUser author = registerUser("pin_author");
        TestUser liker = registerUser("pin_liker");
        UUID postId = createImagePost(author, "pinned post");
        UUID popular = createComment(author, postId, null, "the popular one", null);
        UUID plain = createComment(author, postId, null, "a plain one", null);
        rest.exchange(
                "/api/v1/comments/" + popular + "/like",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(liker)),
                Map.class);

        ResponseEntity<Map> response = getWithAuth("/api/v1/posts/" + postId + "/comments", author);

        List<Map<?, ?>> content = contentOf(response);
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("id")).isEqualTo(popular.toString());
        assertThat(content.get(0).get("pinned")).isEqualTo(true);
        assertThat(content.get(1).get("id")).isEqualTo(plain.toString());
        assertThat(content.get(1).get("pinned")).isEqualTo(false);
    }

    @Test
    void listTopLevelComments_limitZero_returns400() {
        TestUser viewer = registerUser("toplevel_limitzero_viewer");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/posts/" + UUID.randomUUID() + "/comments?limit=0", viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listReplies_limitZero_returns400() {
        TestUser viewer = registerUser("replies_limitzero_viewer");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/comments/" + UUID.randomUUID() + "/replies?limit=0", viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listComments_privatePostStranger_returnsForbidden() {
        TestUser owner = registerUser("vis_owner");
        TestUser stranger = registerUser("vis_stranger");
        UUID postId = createImagePost(owner, "private list");
        createComment(owner, postId, null, "owner comment", null);
        jdbcTemplate.update("UPDATE users SET is_private = TRUE WHERE id = ?", owner.id());

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/posts/" + postId + "/comments", stranger);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("POST_FORBIDDEN");
    }

    @Test
    void createReply_parentFromDifferentPost_returnsNotFound() {
        TestUser attacker = registerUser("xpost_attacker");
        TestUser owner = registerUser("xpost_owner");

        UUID postA = createImagePost(attacker, "attacker post");
        UUID postB = createImagePost(owner, "owner post");
        UUID parentOnB = createComment(owner, postB, null, "owner top-level", null);
        jdbcTemplate.update("UPDATE users SET is_private = TRUE WHERE id = ?", owner.id());

        Map<String, Object> body = new HashMap<>();
        body.put("postId", postA.toString());
        body.put("parentId", parentOnB.toString());
        body.put("content", "cross-post reply");
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postA + "/comments",
                        HttpMethod.POST,
                        new HttpEntity<>(body, authHeaders(attacker)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(replyCount(parentOnB)).isZero();
        assertThat(commentCount(postA)).isZero();
        ResponseEntity<Map> replies =
                getWithAuth("/api/v1/comments/" + parentOnB + "/replies", owner);
        assertThat(contentOf(replies)).isEmpty();
    }

    @Test
    void likeComment_ownComment_succeedsAndIncrementsLikeCount() {
        TestUser author = registerUser("selflike_author");
        UUID postId = createImagePost(author, "self-like post");
        UUID commentId = createComment(author, postId, null, "my own comment", null);

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/comments/" + commentId + "/like",
                        HttpMethod.POST,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(likeCount(commentId)).isEqualTo(1);
    }

    @Test
    void likeComment_ownCommentTwice_returnsConflict() {
        TestUser author = registerUser("selflike_twice");
        UUID postId = createImagePost(author, "self-like twice post");
        UUID commentId = createComment(author, postId, null, "my own comment", null);
        rest.exchange(
                "/api/v1/comments/" + commentId + "/like",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(author)),
                Map.class);

        ResponseEntity<Map> second =
                rest.exchange(
                        "/api/v1/comments/" + commentId + "/like",
                        HttpMethod.POST,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("COMMENT_ALREADY_LIKED");
        assertThat(likeCount(commentId)).isEqualTo(1);
    }

    @Test
    void deleteComment_subtree_reportsTheNumberOfCommentsRemoved() {
        TestUser author = registerUser("delcount_author");
        UUID postId = createImagePost(author, "delete count post");
        Tree tree = buildTree(author, postId);

        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/comments/" + tree.target() + "",
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(deleted).get("deletedCommentCount")).isEqualTo(5);
        assertThat(softDeletedCount(postId)).isEqualTo(5);
        // The sibling outside the subtree is untouched.
        assertThat(topLevelCount(postId)).isEqualTo(1);
    }

    @Test
    void deleteComment_subtree_postCommentCountDropsByTheReportedCount() {
        TestUser author = registerUser("delcount_counter");
        UUID postId = createImagePost(author, "delete counter post");
        Tree tree = buildTree(author, postId);
        int before = commentCount(postId);

        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/comments/" + tree.target(),
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);

        int reported = (Integer) dataOf(deleted).get("deletedCommentCount");
        assertThat(before).isEqualTo(6);
        assertThat(commentCount(postId)).isEqualTo(before - reported);
    }

    @Test
    void deletionScope_matchesWhatTheDeleteActuallyRemoves() {
        TestUser author = registerUser("scope_author");
        UUID postId = createImagePost(author, "scope post");
        Tree tree = buildTree(author, postId);

        ResponseEntity<Map> scope =
                getWithAuth("/api/v1/comments/" + tree.target() + "/deletion-scope", author);
        assertThat(scope.getStatusCode()).isEqualTo(HttpStatus.OK);
        int estimated = (Integer) dataOf(scope).get("deletedCommentCount");

        // The estimate is not the direct-reply count; that is the confusion this endpoint exists
        // to remove.
        assertThat(replyCount(tree.target())).isEqualTo(2);
        assertThat(estimated).isEqualTo(5);

        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/comments/" + tree.target(),
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);
        assertThat(dataOf(deleted).get("deletedCommentCount")).isEqualTo(estimated);
    }

    @Test
    void deletionScope_excludesAlreadySoftDeletedDescendants() {
        TestUser author = registerUser("scope_predeleted");
        UUID postId = createImagePost(author, "scope predeleted post");
        Tree tree = buildTree(author, postId);

        // Removing branch B first leaves T with A, A1, A2 live and B soft-deleted.
        rest.exchange(
                "/api/v1/comments/" + tree.b(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(author)),
                Map.class);

        ResponseEntity<Map> scope =
                getWithAuth("/api/v1/comments/" + tree.target() + "/deletion-scope", author);

        assertThat(scope.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(scope).get("deletedCommentCount")).isEqualTo(4);

        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/comments/" + tree.target(),
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(author)),
                        Map.class);
        assertThat(dataOf(deleted).get("deletedCommentCount")).isEqualTo(4);
    }

    @Test
    void deletionScope_leafComment_returnsOne() {
        TestUser author = registerUser("scope_leaf");
        UUID postId = createImagePost(author, "scope leaf post");
        UUID leaf = createComment(author, postId, null, "a leaf", null);

        ResponseEntity<Map> scope =
                getWithAuth("/api/v1/comments/" + leaf + "/deletion-scope", author);

        assertThat(scope.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(scope).get("deletedCommentCount")).isEqualTo(1);
    }

    @Test
    void deletionScope_callerIsNotTheOwner_returnsNotFound() {
        TestUser author = registerUser("scope_owner");
        TestUser stranger = registerUser("scope_stranger");
        UUID postId = createImagePost(author, "scope ownership post");
        UUID commentId = createComment(author, postId, null, "not yours", null);

        ResponseEntity<Map> scope =
                getWithAuth("/api/v1/comments/" + commentId + "/deletion-scope", stranger);

        assertThat(scope.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(scope.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void deletionScope_deletedComment_returnsNotFound() {
        TestUser author = registerUser("scope_gone");
        UUID postId = createImagePost(author, "scope gone post");
        UUID commentId = createComment(author, postId, null, "about to go", null);
        rest.exchange(
                "/api/v1/comments/" + commentId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(author)),
                Map.class);

        ResponseEntity<Map> scope =
                getWithAuth("/api/v1/comments/" + commentId + "/deletion-scope", author);

        assertThat(scope.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteComment_subtree_deletedEventCarriesTheCount() {
        TestUser author = registerUser("delevent_author");
        UUID postId = createImagePost(author, "delete event post");
        Tree tree = buildTree(author, postId);

        rest.exchange(
                "/api/v1/comments/" + tree.target(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(author)),
                Map.class);

        String payload =
                jdbcTemplate.queryForObject(
                        "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND"
                                + " event_type = 'comment.deleted.v1'",
                        String.class,
                        tree.target());
        assertThat(payload).contains("\"deletedCommentCount\": 5");
    }

    private record Tree(UUID sibling, UUID target, UUID a, UUID b, UUID a1, UUID a2) {}

    /**
     * Builds a tree of a fixed, explicitly recorded shape on the supplied post.
     *
     * <pre>
     * S1            depth 0   sibling of the target, must survive the delete
     * T             depth 0   delete target
     *   A           depth 1
     *     A1        depth 2
     *     A2        depth 2
     *   B           depth 1
     * </pre>
     *
     * Post total 6. Target subtree 5 (1 at depth 0, 2 at depth 1, 2 at depth 2). {@code
     * T.reply_count} is 2, which is deliberately different from the subtree size.
     */
    private Tree buildTree(TestUser user, UUID postId) {
        UUID sibling = createComment(user, postId, null, "sibling S1", null);
        UUID target = createComment(user, postId, null, "target T", null);
        UUID a = createComment(user, postId, target, "branch A", null);
        UUID b = createComment(user, postId, target, "branch B", null);
        UUID a1 = createComment(user, postId, a, "leaf A1", null);
        UUID a2 = createComment(user, postId, a, "leaf A2", null);
        return new Tree(sibling, target, a, b, a1, a2);
    }

    private int softDeletedCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE post_id = ? AND deleted_at IS NOT NULL",
                Integer.class,
                postId);
    }

    private static Map<?, ?> dataOf(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<?, ?>) response.getBody().get("data");
    }

    @Test
    void createComment_timestampsAgreeAtInsert() {
        TestUser author = registerUser("ts_agree");
        UUID postId = createImagePost(author, "timestamp agreement post");
        UUID commentId = createComment(author, postId, null, "untouched since insert", null);

        // Both columns default to NOW(), which is the transaction timestamp, so a freshly
        // inserted row must carry the same value in each. They previously disagreed because
        // created_at came from the JVM clock and updated_at from Postgres, which made every
        // comment look edited from the moment it existed.
        assertThat(createdAt(commentId)).isEqualTo(updatedAt(commentId));

        Map<?, ?> body = commentFromList(author, postId, commentId);
        assertThat(body.get("createdAt")).isEqualTo(body.get("updatedAt"));
    }

    @Test
    void createComment_neverEdited_reportsNoEditSignal() {
        TestUser author = registerUser("edit_fresh");
        UUID postId = createImagePost(author, "fresh comment post");
        UUID commentId = createComment(author, postId, null, "never touched", null);

        assertThat(editedAt(commentId)).isNull();
        assertThat(commentFromList(author, postId, commentId).get("editedAt")).isNull();
    }

    @Test
    void editComment_setsTheEditSignal() {
        TestUser author = registerUser("edit_sets");
        UUID postId = createImagePost(author, "edit sets post");
        UUID commentId = createComment(author, postId, null, "before", null);

        ResponseEntity<Map> edited = editComment(author, commentId, "after");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(editedAt(commentId)).isNotNull();
        assertThat(dataOf(edited).get("editedAt")).isNotNull();
        assertThat(commentFromList(author, postId, commentId).get("editedAt")).isNotNull();
    }

    @Test
    void editComment_secondEdit_advancesTheEditSignal() {
        TestUser author = registerUser("edit_twice");
        UUID postId = createImagePost(author, "edit twice post");
        UUID commentId = createComment(author, postId, null, "first", null);

        editComment(author, commentId, "second");
        OffsetDateTime afterFirst = editedAt(commentId);
        editComment(author, commentId, "third");
        OffsetDateTime afterSecond = editedAt(commentId);

        assertThat(afterFirst).isNotNull();
        assertThat(afterSecond).isNotNull();
        assertThat(afterSecond).isAfterOrEqualTo(afterFirst);
    }

    @Test
    void editComment_secondEdit_responseAndBroadcastCarryTheCurrentUpdatedAt() {
        TestUser author = registerUser("edit_stale_ts");
        UUID postId = createImagePost(author, "stale timestamp post");
        UUID commentId = createComment(author, postId, null, "first", null);

        editComment(author, commentId, "second");
        ResponseEntity<Map> secondEdit = editComment(author, commentId, "third");

        // The trigger writes updated_at during the flush, so a response assembled from the entity
        // before that flush carries the value the previous edit left behind - one edit behind the
        // row, and behind this response's own editedAt, which the author's clock had already
        // advanced. Compared as instants because the row carries the database session's offset
        // while the JSON carries UTC.
        Instant rowUpdatedAt = updatedAt(commentId).toInstant();
        assertThat(instantOf(dataOf(secondEdit).get("updatedAt"))).isEqualTo(rowUpdatedAt);
        assertThat(instantOf(editedCommentBroadcast(commentId).get("updatedAt")))
                .isEqualTo(rowUpdatedAt);
    }

    private static Instant instantOf(Object isoTimestamp) {
        return OffsetDateTime.parse((String) isoTimestamp).toInstant();
    }

    /** The embedded comment projection of the most recent {@code comment.edited.v1} event. */
    private Map<?, ?> editedCommentBroadcast(UUID commentId) {
        String payload =
                jdbcTemplate.queryForObject(
                        "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND"
                                + " event_type = 'comment.edited.v1' ORDER BY created_at DESC"
                                + " LIMIT 1",
                        String.class,
                        commentId);
        Map<?, ?> envelope = new ObjectMapper().readValue(payload, Map.class);
        return (Map<?, ?>) ((Map<?, ?>) envelope.get("data")).get("comment");
    }

    @Test
    void likeComment_movesUpdatedAtButLeavesTheEditSignalAbsent() {
        TestUser author = registerUser("edit_liked_author");
        TestUser liker = registerUser("edit_liked_liker");
        UUID postId = createImagePost(author, "liked comment post");
        UUID commentId = createComment(author, postId, null, "unedited but likeable", null);
        OffsetDateTime updatedAtBefore = updatedAt(commentId);

        rest.exchange(
                "/api/v1/comments/" + commentId + "/like",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(liker)),
                Map.class);

        // The whole point of the column: the row changed, so updated_at moved, but the content
        // did not, so the edit signal must stay absent. Comparing the two timestamps to derive
        // an edited marker is exactly the defect this asserts against.
        assertThat(updatedAt(commentId)).isAfter(updatedAtBefore);
        assertThat(likeCount(commentId)).isEqualTo(1);
        assertThat(editedAt(commentId)).isNull();
        assertThat(commentFromList(author, postId, commentId).get("editedAt")).isNull();
    }

    @Test
    void createReply_movesParentUpdatedAtButLeavesItsEditSignalAbsent() {
        TestUser author = registerUser("edit_replied_author");
        TestUser replier = registerUser("edit_replied_replier");
        UUID postId = createImagePost(author, "replied comment post");
        UUID parentId = createComment(author, postId, null, "unedited but replyable", null);
        OffsetDateTime updatedAtBefore = updatedAt(parentId);

        createComment(replier, postId, parentId, "a reply", null);

        assertThat(updatedAt(parentId)).isAfter(updatedAtBefore);
        assertThat(replyCount(parentId)).isEqualTo(1);
        assertThat(editedAt(parentId)).isNull();
    }

    @Test
    void deleteComment_doesNotSetTheEditSignal() {
        TestUser author = registerUser("edit_deleted");
        UUID postId = createImagePost(author, "deleted comment post");
        UUID commentId = createComment(author, postId, null, "about to go", null);

        rest.exchange(
                "/api/v1/comments/" + commentId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(author)),
                Map.class);

        assertThat(deletedAt(commentId)).isNotNull();
        assertThat(editedAt(commentId)).isNull();
    }

    @Test
    void adminModeration_removeAndRestore_doesNotSetTheEditSignal() {
        TestUser author = registerUser("edit_moderated");
        TestUser moderator = registerUser("edit_moderator");
        jdbcTemplate.update(
                "UPDATE users SET role = CAST(? AS user_role) WHERE id = ?",
                "admin",
                moderator.id());
        UUID postId = createImagePost(author, "moderated comment post");
        UUID commentId = createComment(author, postId, null, "flagged content", null);

        // A fresh token is required: the role is embedded in the access token issued at login.
        TestUser admin = reLogin("edit_moderator");
        ResponseEntity<Map> removed =
                rest.exchange(
                        "/api/v1/admin/comments/" + commentId + "/remove",
                        HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("reason", "test"), authHeaders(admin)),
                        Map.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The moderation tombstone, not the owner's. V95 split the two so that a restore cannot
        // undo an author's own deletion, so an administrative removal must leave deleted_at alone.
        assertThat(adminRemovedAt(commentId)).isNotNull();
        assertThat(deletedAt(commentId)).isNull();
        assertThat(editedAt(commentId)).isNull();

        ResponseEntity<Map> restored =
                rest.exchange(
                        "/api/v1/admin/comments/" + commentId + "/restore",
                        HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("reason", "test"), authHeaders(admin)),
                        Map.class);
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRemovedAt(commentId)).isNull();
        assertThat(deletedAt(commentId)).isNull();
        assertThat(editedAt(commentId)).isNull();
        assertThat(contentOf(commentId)).isEqualTo("flagged content");
    }

    @Test
    void editComment_broadcastPayloadCarriesTheEditSignal() {
        TestUser author = registerUser("edit_broadcast");
        UUID postId = createImagePost(author, "broadcast post");
        UUID commentId = createComment(author, postId, null, "before broadcast", null);

        editComment(author, commentId, "after broadcast");

        String payload =
                jdbcTemplate.queryForObject(
                        "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND"
                                + " event_type = 'comment.edited.v1'",
                        String.class,
                        commentId);
        assertThat(payload).contains("\"editedAt\"");
        assertThat(payload).doesNotContain("\"editedAt\": null");
    }

    private ResponseEntity<Map> editComment(TestUser user, UUID commentId, String content) {
        return rest.exchange(
                "/api/v1/comments/" + commentId,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("content", content), authHeaders(user)),
                Map.class);
    }

    private Map<?, ?> commentFromList(TestUser viewer, UUID postId, UUID commentId) {
        ResponseEntity<Map> response = getWithAuth("/api/v1/posts/" + postId + "/comments", viewer);
        return contentOf(response).stream()
                .filter(c -> commentId.toString().equals(c.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private OffsetDateTime editedAt(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT edited_at FROM comments WHERE id = ?", OffsetDateTime.class, commentId);
    }

    private OffsetDateTime createdAt(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM comments WHERE id = ?", OffsetDateTime.class, commentId);
    }

    private OffsetDateTime updatedAt(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM comments WHERE id = ?", OffsetDateTime.class, commentId);
    }

    private OffsetDateTime deletedAt(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM comments WHERE id = ?", OffsetDateTime.class, commentId);
    }

    private OffsetDateTime adminRemovedAt(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT admin_removed_at FROM comments WHERE id = ?",
                OffsetDateTime.class,
                commentId);
    }

    private String contentOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM comments WHERE id = ?", String.class, commentId);
    }

    private TestUser reLogin(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "identifier",
                                        username + "@test.local",
                                        "password",
                                        "S3cur3P@ssword!"),
                                headers),
                        Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        UUID id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        return new TestUser(id, (String) data.get("accessToken"));
    }

    @Test
    void listComments_defaultMode_returnsEveryCommentExactlyOnceAcrossAllPages() {
        TestUser author = registerUser("sort_dupe_author");
        TestUser liker = registerUser("sort_dupe_liker");
        UUID postId = createImagePost(author, "duplicate pinned post");
        SortFixture f = buildSortFixture(author, liker, postId);

        List<PagedRow> all = pageEverything(author, postId, null, 2);

        // The two pinned comments are the two oldest, so they also sit on the last chronological
        // page. Before the fix they were returned twice: once in the pinned block on page one and
        // again in the body of the last page.
        assertThat(all).extracting(PagedRow::id).doesNotHaveDuplicates();
        assertThat(all)
                .extracting(PagedRow::id)
                .containsExactlyInAnyOrder(f.t1(), f.t2(), f.t3(), f.t4(), f.t5(), f.t6());
    }

    @Test
    void listComments_defaultMode_pinsOnPageOneOnly() {
        TestUser author = registerUser("sort_pin_author");
        TestUser liker = registerUser("sort_pin_liker");
        UUID postId = createImagePost(author, "pin page one post");
        SortFixture f = buildSortFixture(author, liker, postId);

        List<PagedRow> all = pageEverything(author, postId, null, 2);

        assertThat(all.stream().filter(PagedRow::pinned).map(PagedRow::id).toList())
                .containsExactly(f.t1(), f.t2());
        assertThat(all.stream().filter(PagedRow::pinned).map(PagedRow::page).toList())
                .containsOnly(1);
    }

    @Test
    void listComments_newest_returnsPureChronologyWithNoPinnedBlock() {
        TestUser author = registerUser("sort_newest_author");
        TestUser liker = registerUser("sort_newest_liker");
        UUID postId = createImagePost(author, "newest mode post");
        SortFixture f = buildSortFixture(author, liker, postId);

        List<PagedRow> all = pageEverything(author, postId, "newest", 2);

        assertThat(all).noneMatch(PagedRow::pinned);
        // Newest first, and the two comments top would have pinned appear in their chronological
        // position rather than at the head.
        assertThat(all)
                .extracting(PagedRow::id)
                .containsExactly(f.t6(), f.t5(), f.t4(), f.t3(), f.t2(), f.t1());
    }

    @Test
    void listComments_cursorFromDefaultModeReplayedUnderNewest_returns400() {
        TestUser author = registerUser("sort_xcursor_author");
        TestUser liker = registerUser("sort_xcursor_liker");
        UUID postId = createImagePost(author, "cross cursor post");
        buildSortFixture(author, liker, postId);

        ResponseEntity<Map> first =
                getWithAuth("/api/v1/posts/" + postId + "/comments?limit=2", author);
        String cursor = endCursorOf(first);
        assertThat(cursor).isNotNull();

        ResponseEntity<Map> replayed =
                getWithAuth(
                        "/api/v1/posts/"
                                + postId
                                + "/comments?limit=2&sort=newest&cursor="
                                + cursor,
                        author);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replayed.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void listComments_cursorFromNewestReplayedUnderDefaultMode_returns400() {
        TestUser author = registerUser("sort_xcursor2_author");
        TestUser liker = registerUser("sort_xcursor2_liker");
        UUID postId = createImagePost(author, "cross cursor back post");
        buildSortFixture(author, liker, postId);

        ResponseEntity<Map> first =
                getWithAuth("/api/v1/posts/" + postId + "/comments?limit=2&sort=newest", author);
        String cursor = endCursorOf(first);
        assertThat(cursor).isNotNull();

        ResponseEntity<Map> replayed =
                getWithAuth(
                        "/api/v1/posts/" + postId + "/comments?limit=2&cursor=" + cursor, author);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replayed.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void listComments_unrecognisedSortValue_returns400NamingTheAcceptedValues() {
        TestUser author = registerUser("sort_bogus");
        UUID postId = createImagePost(author, "bogus sort post");
        createComment(author, postId, null, "a comment", null);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/posts/" + postId + "/comments?sort=oldest", author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).contains("top").contains("newest");
    }

    @Test
    void listComments_fewerCommentsThanThePinnedBlock_behavesInBothModes() {
        TestUser author = registerUser("sort_small_author");
        TestUser liker = registerUser("sort_small_liker");
        UUID postId = createImagePost(author, "small post");
        UUID only = createComment(author, postId, null, "the only one", null);
        rest.exchange(
                "/api/v1/comments/" + only + "/like",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(liker)),
                Map.class);

        List<PagedRow> top = pageEverything(author, postId, "top", 10);
        List<PagedRow> newest = pageEverything(author, postId, "newest", 10);

        // One comment, one like, so it is eligible for the pinned block and is also the whole
        // chronological body. It must still be returned exactly once.
        assertThat(top).extracting(PagedRow::id).containsExactly(only);
        assertThat(top.get(0).pinned()).isTrue();
        assertThat(newest).extracting(PagedRow::id).containsExactly(only);
        assertThat(newest.get(0).pinned()).isFalse();
    }

    @Test
    void listComments_noComments_behavesInBothModes() {
        TestUser author = registerUser("sort_empty");
        UUID postId = createImagePost(author, "empty post");

        assertThat(pageEverything(author, postId, "top", 10)).isEmpty();
        assertThat(pageEverything(author, postId, "newest", 10)).isEmpty();
    }

    @Test
    void listReplies_ignoresTheSortParameter() {
        TestUser author = registerUser("sort_replies_author");
        TestUser liker = registerUser("sort_replies_liker");
        UUID postId = createImagePost(author, "replies unaffected post");
        UUID parent = createComment(author, postId, null, "parent", null);
        UUID r1 = createComment(author, postId, parent, "reply one", null);
        UUID r2 = createComment(author, postId, parent, "reply two", null);
        rest.exchange(
                "/api/v1/comments/" + r1 + "/like",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(liker)),
                Map.class);

        ResponseEntity<Map> replies =
                getWithAuth("/api/v1/comments/" + parent + "/replies?limit=10", author);

        // The reply list has no pinned block, so a liked reply stays in its chronological place
        // and nothing is prepended.
        assertThat(replies.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(replies).stream().map(c -> (String) c.get("id")).toList())
                .containsExactly(r2.toString(), r1.toString());
        assertThat(contentOf(replies)).allMatch(c -> Boolean.FALSE.equals(c.get("pinned")));
    }

    private record PagedRow(UUID id, boolean pinned, int page) {}

    private record SortFixture(UUID t1, UUID t2, UUID t3, UUID t4, UUID t5, UUID t6) {}

    /**
     * Six top-level comments created oldest first, so the chronological stream is T6 down to T1.
     *
     * <p>T1 receives two likes and T2 one, so the pinned block is exactly {@code [T1, T2]} and the
     * two pinned comments are also the two oldest. At a page size of two that puts them on the
     * first page as pinned and on the last chronological page as body rows, which is the shape that
     * exposes the duplicate.
     */
    private SortFixture buildSortFixture(TestUser author, TestUser liker, UUID postId) {
        UUID t1 = createComment(author, postId, null, "T1 oldest", null);
        UUID t2 = createComment(author, postId, null, "T2", null);
        UUID t3 = createComment(author, postId, null, "T3", null);
        UUID t4 = createComment(author, postId, null, "T4", null);
        UUID t5 = createComment(author, postId, null, "T5", null);
        UUID t6 = createComment(author, postId, null, "T6 newest", null);
        likeAs(liker, t1);
        likeAs(author, t1);
        likeAs(liker, t2);
        return new SortFixture(t1, t2, t3, t4, t5, t6);
    }

    private void likeAs(TestUser user, UUID commentId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/comments/" + commentId + "/like",
                        HttpMethod.POST,
                        new HttpEntity<>(authHeaders(user)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<PagedRow> pageEverything(TestUser viewer, UUID postId, String sort, int limit) {
        List<PagedRow> rows = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 1; page <= 20; page++) {
            String url = "/api/v1/posts/" + postId + "/comments?limit=" + limit;
            if (sort != null) {
                url += "&sort=" + sort;
            }
            if (cursor != null) {
                url += "&cursor=" + cursor;
            }
            ResponseEntity<Map> response = getWithAuth(url, viewer);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            int pageNumber = page;
            contentOf(response)
                    .forEach(
                            c ->
                                    rows.add(
                                            new PagedRow(
                                                    UUID.fromString((String) c.get("id")),
                                                    Boolean.TRUE.equals(c.get("pinned")),
                                                    pageNumber)));
            if (!hasNextPage(response)) {
                return rows;
            }
            cursor = endCursorOf(response);
            assertThat(cursor).as("endCursor while more pages remain").isNotNull();
        }
        throw new AssertionError("pagination did not terminate within 20 pages");
    }

    private static String endCursorOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (String) ((Map<?, ?>) data.get("pageInfo")).get("endCursor");
    }

    private static boolean hasNextPage(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return Boolean.TRUE.equals(((Map<?, ?>) data.get("pageInfo")).get("hasNextPage"));
    }

    private UUID createComment(
            TestUser user, UUID postId, UUID parentId, String content, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("postId", postId.toString());
        if (parentId != null) {
            body.put("parentId", parentId.toString());
        }
        body.put("content", content);
        HttpHeaders headers = authHeaders(user);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postId + "/comments",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private int topLevelCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE post_id = ? AND parent_id IS NULL AND"
                        + " deleted_at IS NULL",
                Integer.class,
                postId);
    }

    private int replyCount(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT reply_count FROM comments WHERE id = ?", Integer.class, commentId);
    }

    private int commentCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
    }

    private int likeCount(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT like_count FROM comments WHERE id = ?", Integer.class, commentId);
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

    private UUID createImagePost(TestUser author, String caption) {
        UUID mediaId = insertMediaAsset(author.id());
        Map<String, Object> payload = new HashMap<>();
        payload.put("caption", caption);
        payload.put("postType", "image");
        payload.put("mediaIds", List.of(mediaId.toString()));
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts",
                        HttpMethod.POST,
                        new HttpEntity<>(payload, authHeaders(author)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private UUID insertMediaAsset(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, ?, CAST(? AS media_type), ?, ?)",
                id,
                ownerId,
                "test/" + id,
                "https://cdn.test/" + id,
                "image",
                "image/jpeg",
                1024L);
        return id;
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
    private static List<Map<?, ?>> contentOf(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<?, ?>>) data.get("content");
    }

    private static String uniqueIp() {
        java.util.Random rand = new java.util.Random();
        return "10." + rand.nextInt(256) + "." + rand.nextInt(256) + "." + (1 + rand.nextInt(254));
    }
}
