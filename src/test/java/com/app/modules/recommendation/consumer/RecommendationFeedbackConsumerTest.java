package com.app.modules.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventJdbcRepository;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class RecommendationFeedbackConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.now(ZoneOffset.UTC);

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private UserEventJdbcRepository userEventJdbcRepository;
    @Mock private GorseClient gorseClient;
    @Mock private Channel channel;

    private ConsumerRetryProperties retryProperties;
    private List<Long> sleptMillis;
    private RecommendationFeedbackConsumer consumer;

    @BeforeEach
    void setUp() {
        retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setRetryBackoffs(List.of(Duration.ofMillis(5), Duration.ZERO));
        sleptMillis = new ArrayList<>();
        consumer =
                new RecommendationFeedbackConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        retryProperties,
                        userEventJdbcRepository,
                        gorseClient,
                        sleptMillis::add);
    }

    private void stubProcessOnce() {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
    }

    @Test
    void isTransient_dataAccessException_returnsTrue() {
        assertThat(consumer.isTransient(new QueryTimeoutException("timeout"))).isTrue();
    }

    @Test
    void isTransient_gorseServerError_returnsTrue() {
        assertThat(
                        consumer.isTransient(
                                HttpServerErrorException.create(
                                        HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null)))
                .isTrue();
    }

    @Test
    void isTransient_gorseClientError_returnsFalse() {
        assertThat(
                        consumer.isTransient(
                                HttpClientErrorException.create(
                                        HttpStatus.BAD_REQUEST, "bad", null, null, null)))
                .isFalse();
    }

    @Test
    void isTransient_unknownRuntimeException_returnsTrue() {
        assertThat(consumer.isTransient(new IllegalStateException("unexpected"))).isTrue();
    }

    @Test
    void consume_postLiked_recordsUserEventAndPushesLikeFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));

        consumer.consume(message, channel);

        verify(userEventJdbcRepository)
                .insertIgnoreDuplicate(
                        EVENT_ID, USER_ID, UserEventType.POST_LIKE, "post", POST_ID, OCCURRED_AT);
        verifyFeedbackPushed("like");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_postSaved_recordsUserEventAndPushesSaveFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(PostEventTypes.POST_SAVED_V1));

        consumer.consume(message, channel);

        verify(userEventJdbcRepository)
                .insertIgnoreDuplicate(
                        EVENT_ID, USER_ID, UserEventType.POST_SAVE, "post", POST_ID, OCCURRED_AT);
        verifyFeedbackPushed("save");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_commentCreated_recordsUserEventAndPushesCommentFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(CommentEventTypes.COMMENT_CREATED_V1));

        consumer.consume(message, channel);

        verify(userEventJdbcRepository)
                .insertIgnoreDuplicate(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_COMMENT,
                        "post",
                        POST_ID,
                        OCCURRED_AT);
        verifyFeedbackPushed("comment");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_postViewed_recordsUserEventAndPushesReadFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(PostEventTypes.POST_VIEWED_V1));

        consumer.consume(message, channel);

        verify(userEventJdbcRepository)
                .insertIgnoreDuplicate(
                        EVENT_ID, USER_ID, UserEventType.POST_VIEW, "post", POST_ID, OCCURRED_AT);
        verifyFeedbackPushed("read");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_impressionWithDwell_sendsDwellAsFeedbackValue() throws Exception {
        stubProcessOnce();
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        EVENT_ID,
                        PostEventTypes.POST_VIEWED_V1,
                        OCCURRED_AT,
                        USER_ID,
                        "post",
                        POST_ID,
                        Map.of(
                                "postId",
                                POST_ID.toString(),
                                "dwellSeconds",
                                4.5,
                                "surface",
                                "feed"));

        consumer.consume(message(envelope), channel);

        ArgumentCaptor<List<GorseFeedback>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).insertFeedback(captor.capture());
        assertThat(captor.getValue().get(0).feedbackType()).isEqualTo("read");
        assertThat(captor.getValue().get(0).value()).isEqualTo(4.5);
    }

    @Test
    void consume_viewWithoutDwell_fallsBackToUnitFeedbackValue() throws Exception {
        stubProcessOnce();

        consumer.consume(message(envelope(PostEventTypes.POST_VIEWED_V1)), channel);

        ArgumentCaptor<List<GorseFeedback>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).insertFeedback(captor.capture());
        assertThat(captor.getValue().get(0).value()).isEqualTo(1.0);
    }

    @Test
    void consume_postShared_recordsUserEventAndPushesShareFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(MessageEventTypes.POST_SHARED_V1));

        consumer.consume(message, channel);

        verify(userEventJdbcRepository)
                .insertIgnoreDuplicate(
                        EVENT_ID, USER_ID, UserEventType.POST_SHARE, "post", POST_ID, OCCURRED_AT);
        verifyFeedbackPushed("share");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_commentLiked_attributesWeakLikeToParentPost() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(CommentEventTypes.COMMENT_LIKED_V1));

        consumer.consume(message, channel);

        verify(userEventJdbcRepository)
                .insertIgnoreDuplicate(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.COMMENT_LIKE,
                        "post",
                        POST_ID,
                        OCCURRED_AT);
        ArgumentCaptor<List<GorseFeedback>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).insertFeedback(captor.capture());
        GorseFeedback feedback = captor.getValue().get(0);
        // The signal is attributed to the parent post, not the comment, and weighted below a
        // direct post like.
        assertThat(feedback.itemId()).isEqualTo(POST_ID.toString());
        assertThat(feedback.feedbackType()).isEqualTo("like");
        assertThat(feedback.value()).isEqualTo(0.5);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_unknownEventType_nacksWithoutRequeueForBrokerDeadLettering() throws Exception {
        Message message = message(envelope("unknown.event.type"));
        stubProcessOnce();

        consumer.consume(message, channel);

        // requeue=false: the queue's own x-dead-letter-exchange routes this to the DLQ; no
        // application-level publish should happen.
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
        verify(gorseClient, never()).insertFeedback(anyList());
    }

    @Test
    void consume_missingPostId_nacksWithoutRequeue() throws Exception {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        EVENT_ID,
                        PostEventTypes.POST_LIKED_V1,
                        OCCURRED_AT,
                        USER_ID,
                        "post",
                        POST_ID,
                        Map.of());
        Message message = message(envelope);
        stubProcessOnce();

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_missingActorId_nacksWithoutRequeue() throws Exception {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        EVENT_ID,
                        PostEventTypes.POST_LIKED_V1,
                        OCCURRED_AT,
                        null,
                        "post",
                        POST_ID,
                        Map.of("postId", POST_ID.toString()));
        Message message = message(envelope);
        stubProcessOnce();

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_gorseTransientFailure_retriesThenNacksWithoutRequeue() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        stubProcessOnce();
        doThrow(
                        HttpServerErrorException.create(
                                HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null))
                .when(gorseClient)
                .insertFeedback(anyList());

        consumer.consume(message, channel);

        verify(gorseClient, times(3)).insertFeedback(anyList());
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
        assertThat(sleptMillis).hasSize(1);
    }

    @Test
    void consume_gorsePermanentFailure_nacksWithoutRetry() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        stubProcessOnce();
        doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null))
                .when(gorseClient)
                .insertFeedback(anyList());

        consumer.consume(message, channel);

        verify(gorseClient, times(1)).insertFeedback(anyList());
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_userEventForeignKeyViolation_nacksWithoutRetry() throws Exception {
        // Reproduces the defect: a stale event whose actor was deleted (e.g. by a seed reset)
        // violates user_events.user_id's foreign key on every attempt, since retrying an insert
        // against a user that no longer exists can never succeed. Before the fix this exception
        // was classified transient and retried to exhaustion (see consume_gorseTransientFailure_
        // retriesThenNacksWithoutRequeue for what that costs); after the fix it must dead-letter
        // on the very first attempt with no wasted retries.
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        stubProcessOnce();
        doThrow(new DataIntegrityViolationException("FK violation"))
                .when(userEventJdbcRepository)
                .insertIgnoreDuplicate(any(), any(), any(), any(), any(), any());

        consumer.consume(message, channel);

        verify(userEventJdbcRepository, times(1))
                .insertIgnoreDuplicate(any(), any(), any(), any(), any(), any());
        verify(gorseClient, never()).insertFeedback(anyList());
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
        assertThat(sleptMillis).isEmpty();
    }

    @Test
    void consume_duplicateDelivery_skipsHandlerAndAcks() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenReturn(ProcessedMessageResult.DUPLICATE);

        consumer.consume(message, channel);

        verify(userEventJdbcRepository, never())
                .insertIgnoreDuplicate(any(), any(), any(), any(), any(), any());
        verify(gorseClient, never()).insertFeedback(anyList());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_nackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(envelope("unknown.event.type"));
        stubProcessOnce();
        doThrow(new IOException("channel closed")).when(channel).basicNack(1L, false, false);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
    }

    private void verifyFeedbackPushed(String feedbackType) {
        ArgumentCaptor<List<GorseFeedback>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).insertFeedback(captor.capture());
        List<GorseFeedback> feedback = captor.getValue();
        assertThat(feedback).hasSize(1);
        assertThat(feedback.get(0).feedbackType()).isEqualTo(feedbackType);
        assertThat(feedback.get(0).userId()).isEqualTo(USER_ID.toString());
        assertThat(feedback.get(0).itemId()).isEqualTo(POST_ID.toString());
        assertThat(feedback.get(0).timestamp()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void consume_binarySignal_sendsUnitFeedbackValue() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));

        consumer.consume(message, channel);

        ArgumentCaptor<List<GorseFeedback>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).insertFeedback(captor.capture());
        assertThat(captor.getValue().get(0).value()).isEqualTo(1.0);
    }

    private Message message(DomainEventEnvelope envelope) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(1L)
                .build();
    }

    private DomainEventEnvelope envelope(String eventType) {
        return new DomainEventEnvelope(
                EVENT_ID,
                eventType,
                OCCURRED_AT,
                USER_ID,
                "post",
                POST_ID,
                Map.of("postId", POST_ID.toString(), "postOwnerId", UUID.randomUUID().toString()));
    }
}
