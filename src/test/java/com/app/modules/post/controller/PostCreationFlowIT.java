package com.app.modules.post.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Drives the post creation features through the HTTP surface against a real database: drafts and
 * scheduling with their limits, the publication time, the owner's lists, co-authors with their
 * counter and visibility, people tags with permission and review, and stored mentions.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class PostCreationFlowIT {

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
        registry.add("JWT_SECRET", () -> "post-creation-flow-it-secret-32-chars!!!!");
        registry.add("JWT_ISSUER", () -> "https://postcreation.it.local");
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
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private record TestUser(UUID id, String username, String token) {}

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM comment_mentions");
        jdbc.update("DELETE FROM comments");
        jdbc.update("DELETE FROM post_mentions");
        jdbc.update("DELETE FROM post_user_tags");
        jdbc.update("DELETE FROM post_collaborators");
        jdbc.update("DELETE FROM post_media");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM post_edit_history");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM places");
        jdbc.update("DELETE FROM blocks");
        jdbc.update("DELETE FROM follows");
        jdbc.update("DELETE FROM user_settings");
        jdbc.update("DELETE FROM users");
    }

    // ------------------------------------------------------------------ drafts and scheduling

    @Test
    void createPost_asDraft_isListedForItsOwnerAndHiddenFromOthers() {
        TestUser owner = createUser("draft_owner");
        TestUser other = createUser("draft_other");

        UUID id =
                createPost(owner, Map.of("postType", "text", "caption", "wip", "status", "draft"));

        assertThat(listOwn(owner, "draft")).contains(id.toString());
        assertThat(get("/api/v1/posts/" + id, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/posts/" + id, owner).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishedAtOf(id)).isNull();
    }

    @Test
    void createPost_scheduledInThePast_isRefused() {
        TestUser owner = createUser("past_owner");

        ResponseEntity<Map> response =
                send(
                        HttpMethod.POST,
                        "/api/v1/posts",
                        Map.of(
                                "postType", "text",
                                "caption", "too late",
                                "status", "scheduled",
                                "scheduledAt", OffsetDateTime.now().minusMinutes(5).toString()),
                        owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("POST_SCHEDULE_INVALID");
        assertThat(countPosts()).isZero();
    }

    @Test
    void createPost_scheduledBeyondTheHorizon_isRefused() {
        TestUser owner = createUser("far_owner");

        ResponseEntity<Map> response =
                send(
                        HttpMethod.POST,
                        "/api/v1/posts",
                        Map.of(
                                "postType", "text",
                                "caption", "too far",
                                "status", "scheduled",
                                "scheduledAt", OffsetDateTime.now().plusDays(31).toString()),
                        owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("POST_SCHEDULE_INVALID");
    }

    @Test
    void createPost_scheduledInsideTheWindow_waitsAndShowsInTheCounts() {
        TestUser owner = createUser("sched_owner");
        createPost(owner, Map.of("postType", "text", "caption", "later", "status", "draft"));
        UUID scheduled =
                createPost(
                        owner,
                        Map.of(
                                "postType", "text",
                                "caption", "tomorrow",
                                "status", "scheduled",
                                "scheduledAt", OffsetDateTime.now().plusDays(1).toString()));

        assertThat(statusOf(scheduled)).isEqualTo("scheduled");
        Map<String, Object> counts = dataOf(get("/api/v1/users/me/posts/counts", owner));
        assertThat(counts.get("draft")).isEqualTo(1);
        assertThat(counts.get("scheduled")).isEqualTo(1);
        assertThat(counts.get("archived")).isEqualTo(0);
        assertThat(counts.get("limit")).isEqualTo(50);
    }

    @Test
    void createPost_pastTheSharedDraftLimit_isRefused() {
        TestUser owner = createUser("limit_owner");
        for (int i = 0; i < 50; i++) {
            jdbc.update(
                    "INSERT INTO posts(user_id, post_type, status, caption) VALUES (?, 'text',"
                            + " 'draft', ?)",
                    owner.id(),
                    "draft " + i);
        }

        ResponseEntity<Map> response =
                send(
                        HttpMethod.POST,
                        "/api/v1/posts",
                        Map.of("postType", "text", "caption", "one too many", "status", "draft"),
                        owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("code")).isEqualTo("POST_DRAFT_LIMIT_REACHED");
    }

    @Test
    void publishDraft_movesTheSamePostAndSetsItsPublicationTime() {
        TestUser owner = createUser("publish_owner");
        UUID id = createPost(owner, Map.of("postType", "text", "caption", "x", "status", "draft"));
        jdbc.update("UPDATE posts SET created_at = NOW() - INTERVAL '10 days' WHERE id = ?", id);

        ResponseEntity<Map> response =
                send(
                        HttpMethod.PATCH,
                        "/api/v1/posts/" + id + "/status",
                        Map.of("targetStatus", "published"),
                        owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countPosts()).isOne();
        assertThat(statusOf(id)).isEqualTo("published");
        // The publication time is the moment of publishing, not the day the draft was written.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at > created_at + INTERVAL '9 days' FROM posts"
                                        + " WHERE id = ?",
                                Boolean.class,
                                id))
                .isTrue();
    }

    @Test
    void archiveAndUnarchive_keepTheOriginalPublicationTime() {
        TestUser owner = createUser("archive_owner");
        UUID id =
                createPost(
                        owner, Map.of("postType", "text", "caption", "x", "status", "published"));
        OffsetDateTime first = publishedAtOf(id);

        send(
                HttpMethod.PATCH,
                "/api/v1/posts/" + id + "/status",
                Map.of("targetStatus", "archived"),
                owner);
        assertThat(listOwn(owner, "archived")).contains(id.toString());
        send(
                HttpMethod.PATCH,
                "/api/v1/posts/" + id + "/status",
                Map.of("targetStatus", "published"),
                owner);

        assertThat(statusOf(id)).isEqualTo("published");
        assertThat(publishedAtOf(id)).isEqualTo(first);
    }

    @Test
    void likeAndSave_onAnUnpublishedPost_areRefusedForItsOwner() {
        TestUser owner = createUser("gate_owner");
        UUID id = createPost(owner, Map.of("postType", "text", "caption", "x", "status", "draft"));

        assertThat(
                        send(HttpMethod.POST, "/api/v1/posts/" + id + "/like", null, owner)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        send(HttpMethod.POST, "/api/v1/posts/" + id + "/save", null, owner)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_likes", Long.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE event_type LIKE"
                                        + " 'post.liked%'",
                                Long.class))
                .isZero();
    }

    // ------------------------------------------------------------------------- co-authors

    @Test
    void collaboration_acceptedCoAuthor_countsOnTheirProfileUntilTheyLeave() {
        TestUser owner = createUser("collab_owner");
        TestUser coAuthor = createUser("collab_author");
        UUID id =
                createPost(
                        owner,
                        Map.of(
                                "postType", "text",
                                "caption", "together",
                                "status", "published",
                                "collaboratorIds", List.of(coAuthor.id().toString())));
        assertThat(postCountOf(coAuthor)).isZero();
        assertThat(collaborationStatusOf(id, coAuthor)).isEqualTo("invited");

        send(HttpMethod.POST, "/api/v1/posts/" + id + "/collaboration/accept", null, coAuthor);

        assertThat(collaborationStatusOf(id, coAuthor)).isEqualTo("accepted");
        assertThat(postCountOf(coAuthor)).isOne();
        List<String> profile = profileIds(owner, coAuthor);
        assertThat(profile).contains(id.toString());

        send(HttpMethod.DELETE, "/api/v1/posts/" + id + "/collaboration", null, coAuthor);

        assertThat(collaborationStatusOf(id, coAuthor)).isEqualTo("left");
        assertThat(postCountOf(coAuthor)).isZero();
    }

    @Test
    void collaboration_fourCoAuthors_areRefused() {
        TestUser owner = createUser("limit_collab_owner");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(createUser("candidate" + i).id().toString());
        }
        UUID id =
                createPost(
                        owner, Map.of("postType", "text", "caption", "x", "status", "published"));

        ResponseEntity<Map> response =
                send(
                        HttpMethod.PUT,
                        "/api/v1/posts/" + id + "/collaborators",
                        Map.of("userIds", ids),
                        owner);

        // The request is bounded at three, so the fourth name is refused before any row exists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_collaborators", Long.class))
                .isZero();
    }

    @Test
    void collaboration_viewerBlockedByAnAcceptedCoAuthor_cannotReadThePost() {
        TestUser owner = createUser("block_owner");
        TestUser coAuthor = createUser("block_coauthor");
        TestUser viewer = createUser("block_viewer");
        UUID id =
                createPost(
                        owner,
                        Map.of(
                                "postType", "text",
                                "caption", "shared",
                                "status", "published",
                                "collaboratorIds", List.of(coAuthor.id().toString())));
        send(HttpMethod.POST, "/api/v1/posts/" + id + "/collaboration/accept", null, coAuthor);
        assertThat(get("/api/v1/posts/" + id, viewer).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                coAuthor.id(),
                viewer.id());

        // A block with a co-author reads exactly as a block with the owner does.
        TestUser blockedByOwner = createUser("block_viewer_owner");
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                owner.id(),
                blockedByOwner.id());
        var ownerBlock = get("/api/v1/posts/" + id, blockedByOwner).getStatusCode();
        assertThat(ownerBlock.is4xxClientError()).isTrue();
        assertThat(get("/api/v1/posts/" + id, viewer).getStatusCode()).isEqualTo(ownerBlock);
    }

    // ------------------------------------------------------------------------------- tags

    @Test
    void tag_withManualApproval_isPendingHiddenFromOthersAndApprovedByTheTaggedPerson() {
        TestUser owner = createUser("tag_owner");
        TestUser tagged = createUser("tag_target");
        TestUser other = createUser("tag_other");
        jdbc.update(
                "UPDATE user_settings SET manual_tag_approval = TRUE WHERE user_id = ?",
                tagged.id());
        UUID media = insertMedia(owner);
        UUID id = createPostWithMedia(owner, media);

        ResponseEntity<Map> put =
                send(
                        HttpMethod.PUT,
                        "/api/v1/posts/" + id + "/tags",
                        Map.of("tags", List.of(tagBody(0, tagged, 0.4, 0.6))),
                        owner);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(tagStatuses(owner, id)).containsExactly("pending");
        assertThat(tagStatuses(other, id)).isEmpty();
        UUID tagId = tagIdOf(id);

        ResponseEntity<Map> approve =
                send(
                        HttpMethod.POST,
                        "/api/v1/posts/" + id + "/tags/" + tagId + "/approve",
                        null,
                        owner);
        // Only the tagged person approves.
        assertThat(approve.getStatusCode().is4xxClientError()).isTrue();
        assertThat(
                        send(
                                        HttpMethod.POST,
                                        "/api/v1/posts/" + id + "/tags/" + tagId + "/approve",
                                        null,
                                        tagged)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tagStatuses(other, id)).containsExactly("approved");
    }

    @Test
    void tag_ofAnAccountThatAllowsNoTags_isRefusedAndWritesNothing() {
        TestUser owner = createUser("deny_owner");
        TestUser tagged = createUser("deny_target");
        jdbc.update(
                "UPDATE user_settings SET tag_permission = CAST('nobody' AS tag_permission) WHERE"
                        + " user_id = ?",
                tagged.id());
        UUID id = createPostWithMedia(owner, insertMedia(owner));

        ResponseEntity<Map> response =
                send(
                        HttpMethod.PUT,
                        "/api/v1/posts/" + id + "/tags",
                        Map.of("tags", List.of(tagBody(0, tagged, 0.5, 0.5))),
                        owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("code")).isEqualTo("POST_TAG_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_user_tags", Long.class)).isZero();
    }

    @Test
    void tag_twentyOneOnOnePhoto_isRefused() {
        TestUser owner = createUser("many_owner");
        UUID id = createPostWithMedia(owner, insertMedia(owner));
        List<Map<String, Object>> tags = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tags.add(tagBody(0, createUser("many_target" + i), 0.5, 0.5));
        }

        ResponseEntity<Map> response =
                send(HttpMethod.PUT, "/api/v1/posts/" + id + "/tags", Map.of("tags", tags), owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("code")).isEqualTo("POST_TAG_LIMIT_REACHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_user_tags", Long.class)).isZero();
    }

    // -------------------------------------------------------------------------- mentions

    @Test
    void captionMention_isStoredOnADraftAndAnnouncedOnlyWhenPublished() {
        TestUser owner = createUser("mention_owner");
        TestUser mentioned = createUser("mention.target");
        UUID id =
                createPost(
                        owner,
                        Map.of(
                                "postType", "text",
                                "caption", "hello @Mention.Target and @nobody_here",
                                "status", "draft"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT mentioned_user_id FROM post_mentions WHERE post_id = ?",
                                UUID.class,
                                id))
                .isEqualTo(mentioned.id());
        assertThat(countEvents("post.mention.created.v1")).isZero();

        send(
                HttpMethod.PATCH,
                "/api/v1/posts/" + id + "/status",
                Map.of("targetStatus", "published"),
                owner);

        assertThat(countEvents("post.mention.created.v1")).isOne();
    }

    @Test
    void commentMention_ofAUsernameWithADot_isStoredInAnyCaseAndAtTheEndOfASentence() {
        TestUser owner = createUser("comment_owner");
        TestUser commenter = createUser("commenter");
        TestUser mentioned = createUser("jane.doe");
        UUID id =
                createPost(
                        owner, Map.of("postType", "text", "caption", "x", "status", "published"));

        for (String content :
                List.of("@jane.doe hi", "@Jane.Doe hi", "thank you @jane.doe.", "(@jane.doe)")) {
            ResponseEntity<Map> response =
                    send(
                            HttpMethod.POST,
                            "/api/v1/posts/" + id + "/comments",
                            Map.of("postId", id.toString(), "content", content),
                            commenter);
            assertThat(response.getStatusCode()).as(content).isEqualTo(HttpStatus.CREATED);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mentions =
                    (List<Map<String, Object>>) dataOf(response).get("mentions");
            assertThat(mentions).as(content).hasSize(1);
            assertThat(mentions.get(0).get("userId"))
                    .as(content)
                    .isEqualTo(mentioned.id().toString());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_mentions", Long.class))
                .isEqualTo(4);
    }

    // --------------------------------------------------------------------------- helpers

    private TestUser createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, 'user', 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local");
        jdbc.update("INSERT INTO user_settings(user_id) VALUES (?)", id);
        return new TestUser(id, username, jwtTokenProvider.generateAccessToken(id, "USER", 0));
    }

    private UUID insertMedia(TestUser owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size, width, height) VALUES (?, ?, ?, ?, 'image',"
                        + " 'image/jpeg', 1000, 100, 100)",
                id,
                owner.id(),
                "k/" + id,
                "https://cdn.example/" + id + ".jpg");
        return id;
    }

    private UUID createPostWithMedia(TestUser owner, UUID media) {
        return createPost(
                owner,
                Map.of(
                        "postType",
                        "image",
                        "caption",
                        "with a photo",
                        "status",
                        "published",
                        "media",
                        List.of(Map.of("mediaId", media.toString()))));
    }

    private static Map<String, Object> tagBody(int position, TestUser user, double x, double y) {
        return Map.of("mediaPosition", position, "userId", user.id().toString(), "x", x, "y", y);
    }

    private UUID createPost(TestUser owner, Map<String, Object> body) {
        ResponseEntity<Map> response = send(HttpMethod.POST, "/api/v1/posts", body, owner);
        assertThat(response.getStatusCode())
                .as("create: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) dataOf(response).get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<String> listOwn(TestUser owner, String status) {
        ResponseEntity<Map> response = get("/api/v1/users/me/posts?status=" + status, owner);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) dataOf(response).get("content"))
                .stream().map(row -> (String) row.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> profileIds(TestUser viewer, TestUser author) {
        ResponseEntity<Map> response =
                get("/api/v1/posts/user/" + author.id() + "?limit=20", viewer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) dataOf(response).get("content"))
                .stream().map(row -> (String) row.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> tagStatuses(TestUser viewer, UUID postId) {
        ResponseEntity<Map> response = get("/api/v1/posts/" + postId, viewer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> media = (List<Map<String, Object>>) dataOf(response).get("media");
        return media.stream()
                .flatMap(item -> ((List<Map<String, Object>>) item.get("tags")).stream())
                .map(tag -> (String) tag.get("status"))
                .toList();
    }

    private UUID tagIdOf(UUID postId) {
        return jdbc.queryForObject(
                "SELECT id FROM post_user_tags WHERE post_id = ?", UUID.class, postId);
    }

    private String statusOf(UUID postId) {
        return jdbc.queryForObject(
                "SELECT CAST(status AS text) FROM posts WHERE id = ?", String.class, postId);
    }

    private OffsetDateTime publishedAtOf(UUID postId) {
        return jdbc.queryForObject(
                "SELECT published_at FROM posts WHERE id = ?", OffsetDateTime.class, postId);
    }

    private String collaborationStatusOf(UUID postId, TestUser user) {
        return jdbc.queryForObject(
                "SELECT CAST(status AS text) FROM post_collaborators WHERE post_id = ? AND"
                        + " user_id = ?",
                String.class,
                postId,
                user.id());
    }

    private int postCountOf(TestUser user) {
        return jdbc.queryForObject(
                "SELECT post_count FROM users WHERE id = ?", Integer.class, user.id());
    }

    private long countPosts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class);
    }

    private long countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Long.class, eventType);
    }

    private ResponseEntity<Map> get(String path, TestUser user) {
        return send(HttpMethod.GET, path, null, user);
    }

    private ResponseEntity<Map> send(HttpMethod method, String path, Object body, TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }
}
