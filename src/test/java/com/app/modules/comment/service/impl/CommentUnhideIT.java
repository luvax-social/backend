package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.ObjectMapper;

/**
 * The post owner's review list of hidden comments and the unhide action, driven over HTTP against a
 * real database, broker and live STOMP endpoint.
 *
 * <p>A comment is hidden at write time by the owner's Hidden Words or interaction limit, or because
 * its parent is hidden. The owner lists every hidden comment of the post and releases one at a
 * time; releasing a comment releases the replies hidden only because of it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.comment.consumer.enabled=true",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=true",
            "app.outbox.publisher.initial-delay=PT0S",
            "app.outbox.publisher.fixed-delay=PT0.3S",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class CommentUnhideIT {

    private static final String HIDDEN_TEXT = "a badword appears here";
    private static final String UNHIDDEN_EVENT = "comment.unhidden.v1";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "comment-unhide-it-secret-32-chars-minimum!!!!");
        r.add("JWT_ISSUER", () -> "https://comment.unhide.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Comment Unhide IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    // Declared at the concrete type because ModerationMailEventHandler injects
    // AbstractTemplateMailSender rather than the interface.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebSocketTicketService webSocketTicketService;
    @Autowired private ObjectMapper objectMapper;

    private final List<StompSession> sessions = new java.util.ArrayList<>();

    private record TestUser(UUID id, String token) {}

    private record Fixture(TestUser owner, TestUser author, TestUser other, UUID postId) {}

    @AfterEach
    void cleanup() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        sessions.clear();
        jdbcTemplate.update("DELETE FROM processed_messages");
        jdbcTemplate.update("DELETE FROM notifications");
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
    @SuppressWarnings("unchecked")
    void ownerList_showsEveryHiddenCommentNewestFirstWithReasonsAndPaginates() {
        Fixture f = fixture("list");
        UUID visible = idOf(post(f.other(), f.postId(), null, "a friendly remark"));
        UUID firstWords = idOf(post(f.author(), f.postId(), null, "first badword"));
        UUID secondWords = idOf(post(f.author(), f.postId(), null, "second badword"));
        UUID underHidden = idOf(post(f.author(), f.postId(), secondWords, "a polite follow-up"));
        addLimit(f.owner());
        UUID limited = idOf(post(f.other(), f.postId(), null, "from a stranger"));
        restampMinutesApart(visible, firstWords, secondWords, underHidden, limited);

        ResponseEntity<Map> firstPage = hiddenList(f.owner(), f.postId(), null, 3);

        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> firstRows = rowsOf(firstPage);
        assertThat(firstRows.stream().map(row -> row.get("id")))
                .containsExactly(
                        limited.toString(), underHidden.toString(), secondWords.toString());
        assertThat(firstRows.stream().map(row -> row.get("hiddenReason")))
                .containsExactly("limited_interaction", "hidden_parent", "hidden_words");
        Map<String, Object> author = (Map<String, Object>) firstRows.get(0).get("author");
        assertThat(author.get("id")).isEqualTo(f.other().id().toString());
        assertThat(firstRows.get(1).get("content")).isEqualTo("a polite follow-up");
        Map<String, Object> firstInfo = pageInfoOf(firstPage);
        assertThat(firstInfo.get("hasNextPage")).isEqualTo(true);

        ResponseEntity<Map> secondPage =
                hiddenList(f.owner(), f.postId(), (String) firstInfo.get("endCursor"), 3);

        assertThat(rowsOf(secondPage).stream().map(row -> row.get("id")))
                .containsExactly(firstWords.toString());
        assertThat(pageInfoOf(secondPage).get("hasNextPage")).isEqualTo(false);
        assertThat(rowsOf(secondPage).get(0).get("hiddenReason")).isEqualTo("hidden_words");
        assertThat(firstRows.stream().map(row -> row.get("id"))).doesNotContain(visible.toString());
    }

    @Test
    void ownerList_leavesOutCommentsByAccountsInABlockWithTheOwnerInEitherDirection() {
        Fixture f = fixture("listblock");
        TestUser control = registerUser("hu_listblock_control");
        UUID ownerBlocked = idOf(post(f.author(), f.postId(), null, "owner blocked badword"));
        UUID blockedOwner = idOf(post(f.other(), f.postId(), null, "blocked owner badword"));
        UUID kept = idOf(post(control, f.postId(), null, "kept badword"));
        block(f.owner(), f.author());
        block(f.other(), f.owner());

        List<Object> listed =
                rowsOf(hiddenList(f.owner(), f.postId(), null, 20)).stream()
                        .map(row -> row.get("id"))
                        .toList();

        assertThat(listed).containsExactly(kept.toString());
        assertThat(listed).doesNotContain(ownerBlocked.toString(), blockedOwner.toString());
    }

    @Test
    void ownerList_leavesOutSoftDeletedAndModeratorRemovedHiddenComments() {
        Fixture f = fixture("listgone");
        UUID kept = idOf(post(f.author(), f.postId(), null, "kept badword"));
        UUID deleted = idOf(post(f.author(), f.postId(), null, "deleted badword"));
        UUID removed = idOf(post(f.author(), f.postId(), null, "removed badword"));
        assertThat(
                        rest.exchange(
                                        "/api/v1/comments/" + deleted,
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(authHeaders(f.author())),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE comments SET admin_removed_at = now() WHERE id = ?", removed);

        List<Object> listed =
                rowsOf(hiddenList(f.owner(), f.postId(), null, 20)).stream()
                        .map(row -> row.get("id"))
                        .toList();

        assertThat(listed).containsExactly(kept.toString());
    }

    @Test
    void ownerList_refusesEveryoneButThePostOwner() {
        Fixture f = fixture("listguard");
        idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));

        ResponseEntity<Map> byAuthor = hiddenList(f.author(), f.postId(), null, 20);
        ResponseEntity<Map> byOther = hiddenList(f.other(), f.postId(), null, 20);
        ResponseEntity<Map> missingPost = hiddenList(f.other(), UUID.randomUUID(), null, 20);
        ResponseEntity<Map> anonymous =
                rest.exchange(
                        "/api/v1/posts/" + f.postId() + "/comments/hidden",
                        HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()),
                        Map.class);

        for (ResponseEntity<Map> refused : List.of(byAuthor, byOther, missingPost)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refused.getBody().get("code")).isEqualTo("POST_NOT_FOUND");
            assertThat(refused.getBody().get("message"))
                    .isEqualTo(missingPost.getBody().get("message"));
        }
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unhide_approvesTheCommentAndMovesBothCountersThroughTheTriggers() {
        Fixture f = fixture("counters");
        UUID parent = idOf(post(f.other(), f.postId(), null, "an ordinary parent"));
        UUID hidden = idOf(post(f.author(), f.postId(), parent, HIDDEN_TEXT));
        assertThat(commentCount(f.postId())).isEqualTo(1);
        assertThat(replyCount(parent)).isZero();

        ResponseEntity<Map> response = unhide(f.owner(), hidden);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("id")).isEqualTo(hidden.toString());
        assertThat(data.get("content")).isEqualTo(HIDDEN_TEXT);
        assertThat(statusOf(hidden)).isEqualTo("approved");
        assertThat(hiddenReasonOf(hidden)).isNull();
        assertThat(commentCount(f.postId())).isEqualTo(2);
        assertThat(replyCount(parent)).isEqualTo(1);
        assertThat(replyIds(f.other(), parent)).containsExactly(hidden);

        List<Map<String, Object>> events = unhiddenEvents(hidden);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event.get("postId")).isEqualTo(f.postId().toString());
        assertThat(event.get("commentId")).isEqualTo(hidden.toString());
        assertThat(event.get("depth")).isEqualTo(1);
        assertThat(event.get("rootId")).isEqualTo(parent.toString());
        assertThat(event.get("parentId")).isEqualTo(parent.toString());
        assertThat(event.get("commentOwnerId")).isEqualTo(f.author().id().toString());
        assertThat(((Map<?, ?>) event.get("comment")).get("content")).isEqualTo(HIDDEN_TEXT);
    }

    @Test
    void unhide_secondCallAnswersNotFoundAndEmitsNothingMore() {
        Fixture f = fixture("twice");
        UUID hidden = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));

        ResponseEntity<Map> first = unhide(f.owner(), hidden);
        ResponseEntity<Map> second = unhide(f.owner(), hidden);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(second.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(unhiddenEvents(hidden)).hasSize(1);
        assertThat(commentCount(f.postId())).isEqualTo(1);
    }

    @Test
    void unhide_refusesEveryoneButThePostOwnerAndLeavesTheCommentHidden() {
        Fixture f = fixture("refusals");
        UUID hidden = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        UUID approved = idOf(post(f.other(), f.postId(), null, "an ordinary remark"));

        ResponseEntity<Map> byAuthor = unhide(f.author(), hidden);
        ResponseEntity<Map> byOther = unhide(f.other(), hidden);
        ResponseEntity<Map> onApproved = unhide(f.owner(), approved);
        ResponseEntity<Map> missing = unhide(f.owner(), UUID.randomUUID());
        ResponseEntity<Map> anonymous =
                rest.exchange(
                        "/api/v1/comments/" + hidden + "/unhide",
                        HttpMethod.POST,
                        new HttpEntity<>(new HttpHeaders()),
                        Map.class);

        for (ResponseEntity<Map> refused : List.of(byAuthor, byOther, onApproved, missing)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refused.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
            assertThat(refused.getBody().get("message"))
                    .isEqualTo(missing.getBody().get("message"));
        }
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(hidden)).isEqualTo("hidden");
        assertThat(unhiddenEvents(hidden)).isEmpty();
    }

    @Test
    void unhide_refusesACommentWrittenByAnAccountThePostOwnerBlocked() {
        Fixture f = fixture("blockedauthor");
        UUID hidden = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        block(f.owner(), f.author());

        ResponseEntity<Map> response = unhide(f.owner(), hidden);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
        assertThat(statusOf(hidden)).isEqualTo("hidden");
    }

    @Test
    void unhide_releasesRepliesHiddenOnlyBecauseOfTheParentAndNothingHiddenForItsOwnReason() {
        Fixture f = fixture("release");
        UUID root = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        UUID onlyBecauseOfParent = idOf(post(f.author(), f.postId(), root, "a polite follow-up"));
        UUID deeperOnlyBecauseOfParent =
                idOf(post(f.author(), f.postId(), onlyBecauseOfParent, "still polite"));
        UUID ownReason = idOf(post(f.author(), f.postId(), root, "another badword reply"));
        UUID underOwnReason = idOf(post(f.author(), f.postId(), ownReason, "polite again"));
        assertThat(hiddenReasonOf(onlyBecauseOfParent)).isEqualTo("hidden_parent");
        assertThat(hiddenReasonOf(ownReason)).isEqualTo("hidden_words");
        assertThat(hiddenReasonOf(underOwnReason)).isEqualTo("hidden_parent");
        assertThat(commentCount(f.postId())).isZero();

        ResponseEntity<Map> response = unhide(f.owner(), root);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (UUID released : List.of(root, onlyBecauseOfParent, deeperOnlyBecauseOfParent)) {
            assertThat(statusOf(released)).as("status of %s", released).isEqualTo("approved");
            assertThat(hiddenReasonOf(released)).isNull();
            assertThat(unhiddenEvents(released)).hasSize(1);
        }
        assertThat(statusOf(ownReason)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(ownReason)).isEqualTo("hidden_words");
        assertThat(statusOf(underOwnReason)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(underOwnReason)).isEqualTo("hidden_parent");
        assertThat(unhiddenEvents(ownReason)).isEmpty();
        assertThat(unhiddenEvents(underOwnReason)).isEmpty();
        assertThat(commentCount(f.postId())).isEqualTo(3);
        assertThat(replyCount(root)).isEqualTo(1);
        assertThat(notificationCount()).isZero();
    }

    @Test
    void unhide_aCommentWhoseParentIsStillHidden_answersConflictUntilTheParentIsReleased() {
        Fixture f = fixture("parentstillhidden");
        UUID parent = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        UUID childOfParent = idOf(post(f.author(), f.postId(), parent, "a polite follow-up"));
        UUID ownReasonChild = idOf(post(f.author(), f.postId(), parent, "more badword talk"));

        ResponseEntity<Map> hiddenParentChild = unhide(f.owner(), childOfParent);
        ResponseEntity<Map> ownReasonUnhide = unhide(f.owner(), ownReasonChild);

        for (ResponseEntity<Map> refused : List.of(hiddenParentChild, ownReasonUnhide)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("code")).isEqualTo("COMMENT_PARENT_STILL_HIDDEN");
        }
        assertThat(statusOf(childOfParent)).isEqualTo("hidden");
        assertThat(statusOf(ownReasonChild)).isEqualTo("hidden");
        assertThat(unhiddenEvents(childOfParent)).isEmpty();

        assertThat(unhide(f.owner(), parent).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(childOfParent)).isEqualTo("approved");
        assertThat(unhide(f.owner(), ownReasonChild).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(ownReasonChild)).isEqualTo("approved");
    }

    @Test
    void unhide_aModeratorRemovedReplyWhoseParentIsHidden_answersNotFoundNotConflict() {
        Fixture f = fixture("removedunderhidden");
        UUID parent = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        UUID child = idOf(post(f.author(), f.postId(), parent, "a polite follow-up"));
        jdbcTemplate.update("UPDATE comments SET admin_removed_at = now() WHERE id = ?", child);

        ResponseEntity<Map> response = unhide(f.owner(), child);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("COMMENT_NOT_FOUND");
    }

    @Test
    void unhide_aHiddenParentReplyEditedIntoAHiddenWord_staysHiddenWhenTheParentIsReleased()
            throws Exception {
        Fixture f = fixture("editedreply");
        TestUser viewer = registerUser("hu_editedreply_viewer");
        UUID parent = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        UUID reply = idOf(post(f.author(), f.postId(), parent, "a polite follow-up"));
        assertThat(hiddenReasonOf(reply)).isEqualTo("hidden_parent");

        ResponseEntity<Map> edited =
                rest.exchange(
                        "/api/v1/comments/" + reply,
                        HttpMethod.PATCH,
                        new HttpEntity<>(
                                Map.of("content", "now a badword"), authHeaders(f.author())),
                        Map.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hiddenReasonOf(reply)).isEqualTo("hidden_words");
        LinkedBlockingQueue<String> frames = subscribe(viewer, f.postId());

        assertThat(unhide(f.owner(), parent).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(statusOf(parent)).isEqualTo("approved");
        assertThat(statusOf(reply)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(reply)).isEqualTo("hidden_words");
        assertThat(unhiddenEvents(reply)).isEmpty();
        // The reply's earlier edit may still be in flight on the live tier, so frames are
        // judged by type and comment rather than by position.
        Set<String> unhiddenFrames = new HashSet<>();
        String frame;
        while ((frame = frames.poll(unhiddenFrames.isEmpty() ? 20 : 2, TimeUnit.SECONDS)) != null) {
            Map<?, ?> payload = objectMapper.readValue(frame, Map.class);
            if (UNHIDDEN_EVENT.equals(payload.get("eventType"))) {
                unhiddenFrames.add((String) ((Map<?, ?>) payload.get("data")).get("commentId"));
            }
        }
        assertThat(unhiddenFrames).containsExactly(parent.toString());
    }

    @Test
    void unhide_deliversALiveFrameForEveryReleasedCommentExceptToABlockedViewer() throws Exception {
        Fixture f = fixture("live");
        TestUser viewer = registerUser("hu_live_viewer");
        TestUser blockedViewer = registerUser("hu_live_blocked");
        block(f.author(), blockedViewer);
        UUID root = idOf(post(f.author(), f.postId(), null, HIDDEN_TEXT));
        UUID reply = idOf(post(f.author(), f.postId(), root, "a polite follow-up"));
        LinkedBlockingQueue<String> viewerFrames = subscribe(viewer, f.postId());
        LinkedBlockingQueue<String> blockedFrames = subscribe(blockedViewer, f.postId());

        assertThat(unhide(f.owner(), root).getStatusCode()).isEqualTo(HttpStatus.OK);

        Set<String> received = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            String frame = viewerFrames.poll(20, TimeUnit.SECONDS);
            assertThat(frame).as("frame %d for the viewer", i + 1).isNotNull();
            Map<?, ?> payload = objectMapper.readValue(frame, Map.class);
            assertThat(payload.get("eventType")).isEqualTo(UNHIDDEN_EVENT);
            received.add((String) ((Map<?, ?>) payload.get("data")).get("commentId"));
        }
        assertThat(received).containsExactlyInAnyOrder(root.toString(), reply.toString());
        assertThat(blockedFrames.poll(2, TimeUnit.SECONDS))
                .as("a viewer in a block with the author receives no unhidden frame")
                .isNull();
        assertThat(notificationCount()).isZero();
    }

    private LinkedBlockingQueue<String> subscribe(TestUser user, UUID postId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String url =
                "ws://localhost:"
                        + port
                        + "/ws/comments/websocket?ticket="
                        + webSocketTicketService.issueTicket(user.token());
        StompSession session =
                client.connectAsync(url, new StompSessionHandlerAdapter() {})
                        .get(15, TimeUnit.SECONDS);
        sessions.add(session);
        assertThat(session.isConnected()).isTrue();
        LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        session.subscribe(
                "/topic/comments." + postId + ".events",
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        frames.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                    }
                });
        return frames;
    }

    private Fixture fixture(String tag) {
        TestUser owner = registerUser("hu_" + tag + "_owner");
        TestUser author = registerUser("hu_" + tag + "_author");
        TestUser other = registerUser("hu_" + tag + "_other");
        UUID postId = createImagePost(owner, tag + " post");
        jdbcTemplate.update(
                "INSERT INTO user_hidden_words (user_id, phrase, normalized_phrase) VALUES (?, ?,"
                        + " ?)",
                owner.id(),
                "badword",
                "badword");
        return new Fixture(owner, author, other, postId);
    }

    // Sequential creates can share a wall-clock instant at the resolution the assertions need, so
    // an ordering test restamps them a minute apart, oldest first.
    private void restampMinutesApart(UUID... oldestFirst) {
        for (int i = 0; i < oldestFirst.length; i++) {
            jdbcTemplate.update(
                    "UPDATE comments SET created_at = now() - make_interval(mins => ?) WHERE id ="
                            + " ?",
                    oldestFirst.length - i,
                    oldestFirst[i]);
        }
    }

    private void addLimit(TestUser owner) {
        jdbcTemplate.update(
                "INSERT INTO interaction_limits (user_id, limit_non_followers,"
                        + " limit_recent_followers, expires_at) VALUES (?, TRUE, FALSE, NOW() +"
                        + " INTERVAL '7 days')",
                owner.id());
    }

    private void block(TestUser blocker, TestUser blocked) {
        jdbcTemplate.update(
                "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)",
                blocker.id(),
                blocked.id());
    }

    private List<Map<String, Object>> unhiddenEvents(UUID commentId) {
        return jdbcTemplate
                .queryForList(
                        "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND"
                                + " event_type = ? ORDER BY created_at",
                        String.class,
                        commentId,
                        UNHIDDEN_EVENT)
                .stream()
                .map(
                        json -> {
                            Map<?, ?> envelope = objectMapper.readValue(json, Map.class);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
                            return data;
                        })
                .toList();
    }

    private String statusOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM comments WHERE id = ?", String.class, commentId);
    }

    private String hiddenReasonOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT hidden_reason FROM comments WHERE id = ?", String.class, commentId);
    }

    private int commentCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
    }

    private int replyCount(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT reply_count FROM comments WHERE id = ?", Integer.class, commentId);
    }

    private int notificationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
    }

    private List<UUID> replyIds(TestUser viewer, UUID parentId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/comments/" + parentId + "/replies",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(viewer)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return rowsOf(response).stream()
                .map(row -> UUID.fromString((String) row.get("id")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return (List<Map<String, Object>>) data.get("content");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfoOf(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return (Map<String, Object>) data.get("pageInfo");
    }

    private ResponseEntity<Map> hiddenList(TestUser user, UUID postId, String cursor, int limit) {
        String path = "/api/v1/posts/" + postId + "/comments/hidden?limit=" + limit;
        if (cursor != null) {
            path += "&cursor=" + cursor;
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> unhide(TestUser user, UUID commentId) {
        return rest.exchange(
                "/api/v1/comments/" + commentId + "/unhide",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(user)),
                Map.class);
    }

    private ResponseEntity<Map> post(TestUser user, UUID postId, UUID parentId, String content) {
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

    private static UUID idOf(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
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
