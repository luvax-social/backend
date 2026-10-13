package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.comment.consumer.CommentNotificationConsumer;
import com.app.modules.comment.live.CommentLiveBlockFilterInterceptor;
import com.app.modules.comment.live.CommentLiveFanoutConsumer;
import com.app.modules.comment.live.CommentLiveServerQueueInitializer;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.comment.observability.CommentMetrics;
import com.app.modules.comment.repository.CommentLikeRepository;
import com.app.modules.mail.service.MailService;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.social.repository.BlockRepository;
import com.app.testsupport.TestContainerImages;
import com.rabbitmq.client.Channel;

/**
 * A comment persisted as hidden belongs to its author alone. Drives every path that could reveal
 * one to somebody else - lists, pins, the context chain, like and unlike by id, reply by parent,
 * edit, delete, report, notification and live delivery - against a post owner whose Hidden Words
 * hide the comment at write time.
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
class CommentHiddenSideChannelIT {

    private static final String HIDDEN_TEXT = "a badword appears here";

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
        r.add("JWT_SECRET", () -> "comment-hidden-side-channel-it-secret-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://comment.hidden.it.local");
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
    @Autowired private BlockRepository blockRepository;
    @Autowired private CommentMetrics commentMetrics;
    @Autowired private DomainEventMessageParser parser;
    @Autowired private ProcessedMessageService processedMessageService;
    @Autowired private NotificationService notificationService;
    @Autowired private ConsumerRetryProperties retryProperties;
    @Autowired private CommentLikeRepository commentLikeRepository;

    private record TestUser(UUID id, String token) {}

    private record Fixture(
            TestUser owner, TestUser author, TestUser other, UUID postId, UUID hidden) {}

    private record LiveFrame(
            String eventType, Map<String, Object> data, Set<UUID> droppedViewers) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM processed_messages");
        jdbcTemplate.update("DELETE FROM notifications");
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM comment_write_idempotency");
        jdbcTemplate.update("DELETE FROM comment_likes");
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
    void list_hiddenCommentVisibleToAuthorOnly() {
        Fixture f = fixture("list");
        UUID visible = idOf(postComment(f.other(), f.postId(), null, "a friendly remark"));

        assertThat(listedIds(f.author(), f.postId())).contains(f.hidden(), visible);
        assertThat(listedIds(f.other(), f.postId())).containsExactly(visible);
        assertThat(listedIds(f.owner(), f.postId())).containsExactly(visible);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_authorsOwnRowCarriesNoHiddenMarker() {
        Fixture f = fixture("marker");
        UUID visible = idOf(postComment(f.author(), f.postId(), null, "a friendly remark"));

        Map<String, Object> hiddenRow = rowOf(f.author(), f.postId(), f.hidden());
        Map<String, Object> visibleRow = rowOf(f.author(), f.postId(), visible);

        assertThat(hiddenRow.keySet()).containsExactlyInAnyOrderElementsOf(visibleRow.keySet());
        assertThat(hiddenRow.toString()).doesNotContainIgnoringCase("hidden_");
        assertThat(hiddenRow.get("content")).isEqualTo(HIDDEN_TEXT);
    }

    @Test
    void list_anonymousViewerNeverSeesHidden() {
        Fixture f = fixture("anon");

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + f.postId() + "/comments",
                        HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()),
                        Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(String.valueOf(response.getBody())).doesNotContain(f.hidden().toString());
    }

    @Test
    void replies_hiddenReplyVisibleToAuthorOnly_andHiddenParentRefusesEveryoneElse() {
        Fixture f = fixture("replies");
        UUID parent = idOf(postComment(f.other(), f.postId(), null, "an ordinary parent"));
        UUID hiddenReply = idOf(postComment(f.author(), f.postId(), parent, HIDDEN_TEXT));

        assertThat(replyIds(f.author(), parent)).containsExactly(hiddenReply);
        assertThat(replyIds(f.other(), parent)).isEmpty();

        ResponseEntity<Map> underHiddenParent =
                get("/api/v1/comments/" + f.hidden() + "/replies", f.other());
        ResponseEntity<Map> underMissingParent =
                get("/api/v1/comments/" + UUID.randomUUID() + "/replies", f.other());
        assertNotFoundLike(underHiddenParent, underMissingParent);
        assertThat(get("/api/v1/comments/" + f.hidden() + "/replies", f.author()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void topLikedPin_neverPinsHiddenComment() {
        Fixture f = fixture("pin");
        UUID visible = idOf(postComment(f.other(), f.postId(), null, "a friendly remark"));
        assertThat(like(f.author(), f.hidden()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(like(f.author(), visible).getStatusCode().is2xxSuccessful()).isTrue();

        for (TestUser viewer : List.of(f.author(), f.other(), f.owner())) {
            List<Map<String, Object>> rows = listRows(viewer, f.postId());
            List<UUID> pinned =
                    rows.stream()
                            .filter(row -> Boolean.TRUE.equals(row.get("pinned")))
                            .map(row -> UUID.fromString((String) row.get("id")))
                            .toList();
            assertThat(pinned).containsExactly(visible);
        }
        assertThat(listedIds(f.author(), f.postId())).contains(f.hidden());
    }

    @Test
    void context_nonAuthorGets404_authorGetsChain() {
        Fixture f = fixture("context");

        ResponseEntity<Map> asOther = get("/api/v1/comments/" + f.hidden() + "/context", f.other());
        ResponseEntity<Map> asMissing =
                get("/api/v1/comments/" + UUID.randomUUID() + "/context", f.other());
        ResponseEntity<Map> asAuthor =
                get("/api/v1/comments/" + f.hidden() + "/context", f.author());

        assertNotFoundLike(asOther, asMissing);
        assertThat(get("/api/v1/comments/" + f.hidden() + "/context", f.owner()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asAuthor.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(asAuthor.getBody())).contains(f.hidden().toString());
    }

    @Test
    void likeById_nonAuthor404() {
        Fixture f = fixture("like");

        ResponseEntity<Map> byOther = like(f.other(), f.hidden());
        ResponseEntity<Map> byOwner = like(f.owner(), f.hidden());
        ResponseEntity<Map> missing = like(f.other(), UUID.randomUUID());
        ResponseEntity<Map> unlikeByOther = unlike(f.other(), f.hidden());
        ResponseEntity<Map> unlikeMissing = unlike(f.other(), UUID.randomUUID());

        assertNotFoundLike(byOther, missing);
        assertNotFoundLike(byOwner, missing);
        assertNotFoundLike(unlikeByOther, unlikeMissing);
        assertThat(like(f.author(), f.hidden()).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void replyWithHiddenParent_nonAuthor404() {
        Fixture f = fixture("reply");

        ResponseEntity<Map> byOther = postComment(f.other(), f.postId(), f.hidden(), "a reply");
        ResponseEntity<Map> byOwner = postComment(f.owner(), f.postId(), f.hidden(), "a reply");
        ResponseEntity<Map> missing =
                postComment(f.other(), f.postId(), UUID.randomUUID(), "a reply");

        assertNotFoundLike(byOther, missing);
        assertNotFoundLike(byOwner, missing);
        assertThat(totalComments(f.postId())).isEqualTo(1);
    }

    @Test
    void replyWithHiddenParent_authorMayReplyToOwnHiddenComment() {
        Fixture f = fixture("selfreply");

        ResponseEntity<Map> reply =
                postComment(f.author(), f.postId(), f.hidden(), "a polite follow-up");

        UUID replyId = idOf(reply);
        assertThat(totalComments(f.postId())).isEqualTo(2);
        assertThat(replyIds(f.author(), f.hidden())).containsExactly(replyId);
        assertThat(commentCount(f.postId())).isZero();
    }

    @Test
    void replyWithHiddenParent_authorsReplyIsKeptOffEveryoneElsesPaths() {
        Fixture f = fixture("selfreplyhidden");
        UUID replyId = idOf(postComment(f.author(), f.postId(), f.hidden(), "a polite follow-up"));

        assertThat(statusOf(replyId)).isEqualTo("hidden");
        assertThat(eventVisibility(replyId, CommentEventTypes.COMMENT_CREATED_V1))
                .isEqualTo("hidden");
        assertThat(get("/api/v1/comments/" + replyId + "/context", f.other()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void editAndDeleteByNonAuthor_answerNotFoundForHiddenComment() {
        Fixture f = fixture("mutate");

        ResponseEntity<Map> edit = editComment(f.other(), f.hidden(), "tampering");
        ResponseEntity<Map> missingEdit = editComment(f.other(), UUID.randomUUID(), "tampering");
        ResponseEntity<Map> delete = deleteComment(f.other(), f.hidden());
        ResponseEntity<Map> missingDelete = deleteComment(f.other(), UUID.randomUUID());

        assertNotFoundLike(edit, missingEdit);
        assertNotFoundLike(delete, missingDelete);
        assertThat(contentOf(f.hidden())).isEqualTo(HIDDEN_TEXT);
    }

    @Test
    void reportById_nonAuthorNonOwner404_ownerAllowed() {
        Fixture f = fixture("report");

        ResponseEntity<Map> byOther = report(f.other(), f.hidden());
        ResponseEntity<Map> missing = report(f.other(), UUID.randomUUID());
        ResponseEntity<Map> byOwner = report(f.owner(), f.hidden());

        assertNotFoundLike(byOther, missing);
        assertThat(byOther.getBody().get("code")).isEqualTo("REPORT_TARGET_NOT_FOUND");
        assertThat(byOwner.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void reportById_authorOfHiddenCommentGetsTheSameAnswerAsForAVisibleOne() {
        Fixture f = fixture("selfreport");
        UUID visible = idOf(postComment(f.author(), f.postId(), null, "a friendly remark"));

        ResponseEntity<Map> onHidden = report(f.author(), f.hidden());
        ResponseEntity<Map> onVisible = report(f.author(), visible);

        assertThat(onHidden.getStatusCode()).isEqualTo(onVisible.getStatusCode());
        assertThat(onHidden.getBody().get("code")).isEqualTo(onVisible.getBody().get("code"));
        assertThat(onHidden.getBody().get("code")).isEqualTo("REPORT_SELF_NOT_ALLOWED");
    }

    @Test
    void notification_noRowsForHiddenCommentOrItsMentions() throws Exception {
        Fixture f = fixture("notify");
        TestUser mentioned = registerUser("hs_notify_mentioned");
        UUID hiddenWithMention =
                idOf(
                        postComment(
                                f.author(),
                                f.postId(),
                                null,
                                "a badword and @hs_notify_mentioned"));
        UUID visibleWithMention =
                idOf(postComment(f.author(), f.postId(), null, "kindly @hs_notify_mentioned"));

        runNotificationConsumer(hiddenWithMention, CommentEventTypes.COMMENT_CREATED_V1);

        assertThat(notificationsFor(f.owner())).isZero();
        assertThat(notificationsFor(mentioned)).isZero();

        runNotificationConsumer(visibleWithMention, CommentEventTypes.COMMENT_CREATED_V1);

        assertThat(notificationsFor(f.owner())).isEqualTo(1);
        assertThat(notificationsFor(mentioned)).isEqualTo(1);
    }

    @Test
    void live_noCreatedFrameForHiddenComment() {
        Fixture f = fixture("livecreate");
        UUID visible = idOf(postComment(f.other(), f.postId(), null, "a friendly remark"));

        assertThat(liveFrame(f.hidden(), CommentEventTypes.COMMENT_CREATED_V1)).isEmpty();
        assertThat(liveFrame(visible, CommentEventTypes.COMMENT_CREATED_V1)).isPresent();
    }

    @Test
    void live_noLikeFrameForHiddenComment() {
        Fixture f = fixture("livelike");
        like(f.author(), f.hidden());

        assertThat(liveFrame(f.hidden(), CommentEventTypes.COMMENT_LIKED_V1)).isEmpty();
    }

    @Test
    void live_editThatNewlyHidesAComment_becomesARemovalForOtherViewersWithoutText() {
        Fixture f = fixture("liveedit");
        UUID visible = idOf(postComment(f.author(), f.postId(), null, "perfectly fine"));

        editComment(f.author(), visible, "now a badword sneaks in");

        LiveFrame frame = liveFrame(visible, CommentEventTypes.COMMENT_EDITED_V1).orElseThrow();
        assertThat(frame.eventType()).isEqualTo(CommentEventTypes.COMMENT_DELETED_V1);
        assertThat(frame.data().get("commentId")).isEqualTo(visible.toString());
        assertThat(frame.data().get("deletedCommentCount")).isEqualTo(1);
        assertThat(frame.data()).doesNotContainKeys("comment", "content", "visibility");
        assertThat(frame.data().toString()).doesNotContain("badword");
        assertThat(frame.droppedViewers()).contains(f.author().id());
    }

    @Test
    void live_editOfAnAlreadyHiddenComment_removesNothingFromViewers() {
        Fixture f = fixture("liveeditagain");

        editComment(f.author(), f.hidden(), "polite words now");

        LiveFrame frame = liveFrame(f.hidden(), CommentEventTypes.COMMENT_EDITED_V1).orElseThrow();
        assertThat(frame.eventType()).isEqualTo(CommentEventTypes.COMMENT_DELETED_V1);
        assertThat(frame.data().get("deletedCommentCount")).isEqualTo(0);
        assertThat(frame.data().toString()).doesNotContain("polite words");
        assertThat(frame.droppedViewers()).contains(f.author().id());
    }

    @Test
    void deleteHiddenComment_liveFrameCarriesZeroVisibleCount() {
        Fixture f = fixture("livedelete");

        ResponseEntity<Map> deleted = deleteComment(f.author(), f.hidden());

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) deleted.getBody().get("data")).get("deletedCommentCount"))
                .isEqualTo(1);
        LiveFrame frame = liveFrame(f.hidden(), CommentEventTypes.COMMENT_DELETED_V1).orElseThrow();
        assertThat(frame.data().get("deletedCommentCount")).isEqualTo(0);
        assertThat(frame.data()).doesNotContainKey("visibleDeletedCount");
    }

    @Test
    void deleteVisibleCommentWithHiddenReply_liveFrameCountsOnlyVisibleRows() {
        Fixture f = fixture("livesubtree");
        UUID parent = idOf(postComment(f.other(), f.postId(), null, "an ordinary parent"));
        idOf(postComment(f.other(), f.postId(), parent, "an ordinary reply"));
        idOf(postComment(f.author(), f.postId(), parent, HIDDEN_TEXT));

        ResponseEntity<Map> deleted = deleteComment(f.other(), parent);

        assertThat(((Map<?, ?>) deleted.getBody().get("data")).get("deletedCommentCount"))
                .isEqualTo(3);
        LiveFrame frame = liveFrame(parent, CommentEventTypes.COMMENT_DELETED_V1).orElseThrow();
        assertThat(frame.data().get("deletedCommentCount")).isEqualTo(2);
    }

    private Fixture fixture(String tag) {
        TestUser owner = registerUser("hs_" + tag + "_owner");
        TestUser author = registerUser("hs_" + tag + "_author");
        TestUser other = registerUser("hs_" + tag + "_other");
        UUID postId = createImagePost(owner, tag + " post");
        addHiddenWord(owner, "badword");
        UUID hidden = idOf(postComment(author, postId, null, HIDDEN_TEXT));
        assertThat(statusOf(hidden)).isEqualTo("hidden");
        return new Fixture(owner, author, other, postId, hidden);
    }

    private void assertNotFoundLike(ResponseEntity<Map> actual, ResponseEntity<Map> missing) {
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(actual.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(actual.getBody().get("code")).isEqualTo(missing.getBody().get("code"));
        assertThat(actual.getBody().get("message")).isEqualTo(missing.getBody().get("message"));
    }

    private Optional<LiveFrame> liveFrame(UUID commentId, String eventType) {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        CommentLiveServerQueueInitializer queue = mock(CommentLiveServerQueueInitializer.class);
        when(queue.getServerId()).thenReturn("it-server");
        CommentLiveFanoutConsumer consumer =
                new CommentLiveFanoutConsumer(
                        parser, template, commentMetrics, queue, blockRepository);

        consumer.consume(messageFor(commentId, eventType));

        List<Invocation> sent =
                mockingDetails(template).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("convertAndSend"))
                        .toList();
        if (sent.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> payload = sent.get(0).getArgument(1);
        Map<String, Object> headers = sent.get(0).getArgument(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        @SuppressWarnings("unchecked")
        Set<UUID> dropped =
                (Set<UUID>)
                        headers.get(
                                CommentLiveBlockFilterInterceptor.BLOCKED_COUNTERPARTIES_HEADER);
        return Optional.of(new LiveFrame((String) payload.get("eventType"), data, dropped));
    }

    private void runNotificationConsumer(UUID commentId, String eventType) throws Exception {
        CommentNotificationConsumer consumer =
                new CommentNotificationConsumer(
                        parser,
                        processedMessageService,
                        notificationService,
                        retryProperties,
                        commentLikeRepository);
        consumer.consume(messageFor(commentId, eventType), mock(Channel.class));
    }

    private Message messageFor(UUID commentId, String eventType) {
        DomainEventEnvelope envelope = latestEvent(commentId, eventType);
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(0L)
                .build();
    }

    private DomainEventEnvelope latestEvent(UUID commentId, String eventType) {
        String json =
                jdbcTemplate.queryForObject(
                        "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND"
                                + " event_type = ? ORDER BY created_at DESC LIMIT 1",
                        String.class,
                        commentId,
                        eventType);
        return DomainEventEnvelopeJson.read(json);
    }

    private String eventVisibility(UUID commentId, String eventType) {
        return (String) latestEvent(commentId, eventType).data().get("visibility");
    }

    private int notificationsFor(TestUser user) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Integer.class,
                user.id());
    }

    private List<Map<String, Object>> listRows(TestUser viewer, UUID postId) {
        ResponseEntity<Map> response = get("/api/v1/posts/" + postId + "/comments", viewer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return rowsOf(response);
    }

    private List<UUID> listedIds(TestUser viewer, UUID postId) {
        return listRows(viewer, postId).stream()
                .map(row -> UUID.fromString((String) row.get("id")))
                .toList();
    }

    private List<UUID> replyIds(TestUser viewer, UUID parentId) {
        ResponseEntity<Map> response = get("/api/v1/comments/" + parentId + "/replies", viewer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return rowsOf(response).stream()
                .map(row -> UUID.fromString((String) row.get("id")))
                .toList();
    }

    private Map<String, Object> rowOf(TestUser viewer, UUID postId, UUID commentId) {
        return listRows(viewer, postId).stream()
                .filter(row -> commentId.toString().equals(row.get("id")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return (List<Map<String, Object>>) data.get("content");
    }

    private ResponseEntity<Map> get(String path, TestUser user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> like(TestUser user, UUID commentId) {
        return rest.exchange(
                "/api/v1/comments/" + commentId + "/like",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(user)),
                Map.class);
    }

    private ResponseEntity<Map> unlike(TestUser user, UUID commentId) {
        return rest.exchange(
                "/api/v1/comments/" + commentId + "/like",
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(user)),
                Map.class);
    }

    private ResponseEntity<Map> report(TestUser user, UUID commentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("reportType", "comment");
        body.put("reportReason", "spam");
        body.put("entityId", commentId.toString());
        return rest.exchange(
                "/api/v1/reports",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(user)),
                Map.class);
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

    private ResponseEntity<Map> deleteComment(TestUser user, UUID commentId) {
        return rest.exchange(
                "/api/v1/comments/" + commentId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(user)),
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

    private String statusOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM comments WHERE id = ?", String.class, commentId);
    }

    private String contentOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM comments WHERE id = ?", String.class, commentId);
    }

    private int commentCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
    }

    private int totalComments(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments WHERE post_id = ?", Integer.class, postId);
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
