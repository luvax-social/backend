package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.app.testsupport.TestContainerImages;

/**
 * Drives comment create and edit over HTTP against a post owner whose Hidden Words, interaction
 * limit or blocks decide whether the comment is shown, hidden or refused.
 */
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
class CommentPolicyWriteIT {

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
        r.add("JWT_SECRET", () -> "comment-policy-write-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://comment.policy.it.local");
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
        jdbcTemplate.update("DELETE FROM comment_write_idempotency");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM interaction_limits");
        jdbcTemplate.update("DELETE FROM user_hidden_words");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM oauth_accounts");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void create_cleanComment_isApprovedCountedAndEnqueuedVisible() {
        TestUser owner = registerUser("pw_clean_owner");
        TestUser actor = registerUser("pw_clean_actor");
        UUID postId = createImagePost(owner, "clean post");

        ResponseEntity<Map> response = postComment(actor, postId, null, "a friendly remark");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID commentId = idOf(response);
        assertThat(statusOf(commentId)).isEqualTo("approved");
        assertThat(hiddenReasonOf(commentId)).isNull();
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(createdVisibility(commentId)).isEqualTo("visible");
    }

    @Test
    void create_hiddenWordInPostOwnersList_persistsHiddenAndIsNotCounted() {
        TestUser owner = registerUser("pw_word_owner");
        TestUser actor = registerUser("pw_word_actor");
        UUID postId = createImagePost(owner, "word post");
        addHiddenWord(owner, "badword");

        ResponseEntity<Map> response = postComment(actor, postId, null, "this has a badword in it");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID commentId = idOf(response);
        assertThat(statusOf(commentId)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(commentId)).isEqualTo("hidden_words");
        assertThat(commentCount(postId)).isZero();
        assertThat(createdVisibility(commentId)).isEqualTo("hidden");
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_responseToTheAuthor_carriesNoHiddenMarker() {
        TestUser owner = registerUser("pw_marker_owner");
        TestUser actor = registerUser("pw_marker_actor");
        UUID postId = createImagePost(owner, "marker post");
        addHiddenWord(owner, "badword");

        ResponseEntity<Map> hidden = postComment(actor, postId, null, "a badword appears");
        ResponseEntity<Map> visible = postComment(actor, postId, null, "a kind word appears");

        Map<String, Object> hiddenData = (Map<String, Object>) hidden.getBody().get("data");
        Map<String, Object> visibleData = (Map<String, Object>) visible.getBody().get("data");
        assertThat(hiddenData.keySet()).containsExactlyInAnyOrderElementsOf(visibleData.keySet());
        assertThat(hiddenData.toString()).doesNotContainIgnoringCase("hidden");
    }

    @Test
    void create_limitedNonFollower_persistsHiddenWithLimitedInteractionReason() {
        TestUser owner = registerUser("pw_limit_owner");
        TestUser actor = registerUser("pw_limit_actor");
        UUID postId = createImagePost(owner, "limit post");
        limitInteractions(owner, true, false);

        ResponseEntity<Map> response = postComment(actor, postId, null, "a friendly remark");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID commentId = idOf(response);
        assertThat(statusOf(commentId)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(commentId)).isEqualTo("limited_interaction");
        assertThat(commentCount(postId)).isZero();
    }

    @Test
    void create_hiddenReply_isNotCountedOnItsParent() {
        TestUser owner = registerUser("pw_reply_owner");
        TestUser actor = registerUser("pw_reply_actor");
        UUID postId = createImagePost(owner, "reply post");
        UUID parent = idOf(postComment(owner, postId, null, "the owner's own remark"));
        addHiddenWord(owner, "badword");

        UUID reply = idOf(postComment(actor, postId, parent, "a badword reply"));

        assertThat(statusOf(reply)).isEqualTo("hidden");
        assertThat(replyCount(parent)).isZero();
        assertThat(commentCount(postId)).isEqualTo(1);
    }

    @Test
    void create_ownerCommentingOnOwnPost_isNeverHidden() {
        TestUser owner = registerUser("pw_self_owner");
        UUID postId = createImagePost(owner, "self post");
        addHiddenWord(owner, "badword");

        UUID commentId = idOf(postComment(owner, postId, null, "my own badword"));

        assertThat(statusOf(commentId)).isEqualTo("approved");
    }

    @Test
    void create_allCapsSpam_isRejectedWithModerationErrorAndNothingIsStored() {
        TestUser owner = registerUser("pw_spam_owner");
        TestUser actor = registerUser("pw_spam_actor");
        UUID postId = createImagePost(owner, "spam post");
        addHiddenWord(owner, "badword");

        ResponseEntity<Map> response =
                postComment(
                        actor,
                        postId,
                        null,
                        "BADWORD BADWORD LOUD LOUD LOUD SHOUTING AT EVERYONE HERE TODAY NOW");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("code")).isEqualTo("COMMENT_MODERATION_REJECTED");
        assertThat(totalComments(postId)).isZero();
    }

    @Test
    void create_actorBlockedByPostOwner_isRestricted() {
        TestUser owner = registerUser("pw_block_owner");
        TestUser actor = registerUser("pw_block_actor");
        UUID postId = createImagePost(owner, "block post");
        block(owner, actor);

        ResponseEntity<Map> response = postComment(actor, postId, null, "let me in");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("POST_COMMENTING_RESTRICTED");
        assertThat(totalComments(postId)).isZero();
    }

    @Test
    void create_replyToAuthorWhoBlockedTheReplier_answersCommentNotFound() {
        TestUser owner = registerUser("pw_pblock_owner");
        TestUser parentAuthor = registerUser("pw_pblock_parent");
        TestUser actor = registerUser("pw_pblock_actor");
        UUID postId = createImagePost(owner, "parent block post");
        UUID parent = idOf(postComment(parentAuthor, postId, null, "the parent remark"));
        block(parentAuthor, actor);

        ResponseEntity<Map> response = postComment(actor, postId, parent, "replying anyway");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(totalComments(postId)).isEqualTo(1);
    }

    @Test
    void create_replyToAuthorTheReplierBlocked_answersCommentNotFound() {
        TestUser owner = registerUser("pw_rblock_owner");
        TestUser parentAuthor = registerUser("pw_rblock_parent");
        TestUser actor = registerUser("pw_rblock_actor");
        UUID postId = createImagePost(owner, "reverse block post");
        UUID parent = idOf(postComment(parentAuthor, postId, null, "the parent remark"));
        block(actor, parentAuthor);

        ResponseEntity<Map> response = postComment(actor, postId, parent, "replying anyway");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void edit_approvedCommentGainingHiddenWord_becomesHiddenAndUncounted() {
        TestUser owner = registerUser("pw_edit_owner");
        TestUser actor = registerUser("pw_edit_actor");
        UUID postId = createImagePost(owner, "edit post");
        addHiddenWord(owner, "badword");
        UUID commentId = idOf(postComment(actor, postId, null, "perfectly fine"));
        assertThat(commentCount(postId)).isEqualTo(1);

        ResponseEntity<Map> edited = editComment(actor, commentId, "now a badword sneaks in");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(commentId)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(commentId)).isEqualTo("hidden_words");
        assertThat(commentCount(postId)).isZero();
    }

    @Test
    void edit_hiddenCommentLosingItsHiddenWord_staysHidden() {
        TestUser owner = registerUser("pw_stay_owner");
        TestUser actor = registerUser("pw_stay_actor");
        UUID postId = createImagePost(owner, "stay post");
        addHiddenWord(owner, "badword");
        UUID commentId = idOf(postComment(actor, postId, null, "a badword here"));

        ResponseEntity<Map> edited = editComment(actor, commentId, "now entirely polite");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(commentId)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(commentId)).isEqualTo("hidden_words");
        assertThat(commentCount(postId)).isZero();
    }

    @Test
    void edit_ownerEditingOwnComment_isNeverHidden() {
        TestUser owner = registerUser("pw_oedit_owner");
        UUID postId = createImagePost(owner, "owner edit post");
        addHiddenWord(owner, "badword");
        UUID commentId = idOf(postComment(owner, postId, null, "polite words"));

        ResponseEntity<Map> edited = editComment(owner, commentId, "a badword from me");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(commentId)).isEqualTo("approved");
    }

    @Test
    void edit_authorNowBlockedByPostOwner_isRestrictedAndContentUnchanged() {
        TestUser owner = registerUser("pw_eblock_owner");
        TestUser actor = registerUser("pw_eblock_actor");
        UUID postId = createImagePost(owner, "edit block post");
        UUID commentId = idOf(postComment(actor, postId, null, "before the block"));
        block(owner, actor);

        ResponseEntity<Map> edited = editComment(actor, commentId, "after the block");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(edited.getBody().get("code")).isEqualTo("POST_COMMENTING_RESTRICTED");
        assertThat(contentOf(commentId)).isEqualTo("before the block");
    }

    private ResponseEntity<Map> postComment(
            TestUser user, UUID postId, UUID parentId, String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("postId", postId.toString());
        if (parentId != null) {
            body.put("parentId", parentId.toString());
        }
        body.put("content", content);
        return rest.exchange(
                "/api/v1/posts/" + postId + "/comments",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(user)),
                Map.class);
    }

    private ResponseEntity<Map> editComment(TestUser user, UUID commentId, String content) {
        return rest.exchange(
                "/api/v1/comments/" + commentId,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("content", content), authHeaders(user)),
                Map.class);
    }

    private static UUID idOf(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private void addHiddenWord(TestUser owner, String word) {
        jdbcTemplate.update(
                "INSERT INTO user_hidden_words (user_id, phrase, normalized_phrase) VALUES (?, ?,"
                        + " ?)",
                owner.id(),
                word,
                word);
    }

    private void limitInteractions(TestUser owner, boolean nonFollowers, boolean recentFollowers) {
        jdbcTemplate.update(
                "INSERT INTO interaction_limits (user_id, limit_non_followers,"
                        + " limit_recent_followers, expires_at) VALUES (?, ?, ?, NOW() + INTERVAL"
                        + " '7 days')",
                owner.id(),
                nonFollowers,
                recentFollowers);
    }

    private void block(TestUser blocker, TestUser blocked) {
        jdbcTemplate.update(
                "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)",
                blocker.id(),
                blocked.id());
    }

    private String statusOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM comments WHERE id = ?", String.class, commentId);
    }

    private String hiddenReasonOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT hidden_reason FROM comments WHERE id = ?", String.class, commentId);
    }

    private String contentOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM comments WHERE id = ?", String.class, commentId);
    }

    private int commentCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
    }

    private int replyCount(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT reply_count FROM comments WHERE id = ?", Integer.class, commentId);
    }

    private int totalComments(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE post_id = ?", Integer.class, postId);
    }

    private String createdVisibility(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT payload->'data'->>'visibility' FROM outbox_events WHERE aggregate_id = ?"
                        + " AND event_type = 'comment.created.v1'",
                String.class,
                commentId);
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
        UUID mediaId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, ?, CAST(? AS media_type), ?, ?)",
                mediaId,
                author.id(),
                "test/" + mediaId,
                "https://cdn.test/" + mediaId,
                "image",
                "image/jpeg",
                1024L);
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

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String uniqueIp() {
        java.util.Random rand = new java.util.Random();
        return "10." + rand.nextInt(256) + "." + rand.nextInt(256) + "." + (1 + rand.nextInt(254));
    }
}
