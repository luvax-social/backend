package com.app.modules.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.app.common.analytics.ClickHouseRequestRejectedException;
import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.ClickHouseUnavailableException.Reason;
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
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.users.service.UserSummaryService;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class RecommendationFeedbackConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.now(ZoneOffset.UTC);

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private UserEventAnalyticsRepository userEventAnalyticsRepository;
    @Mock private UserSummaryService userSummaryService;
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
        lenient().when(userSummaryService.exists(USER_ID)).thenReturn(true);
        consumer =
                new RecommendationFeedbackConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        retryProperties,
                        userEventAnalyticsRepository,
                        userSummaryService,
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

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_LIKE,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "like",
                        1.0);
        verifyFeedbackPushed("like");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_postSaved_recordsUserEventAndPushesSaveFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(PostEventTypes.POST_SAVED_V1));

        consumer.consume(message, channel);

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_SAVE,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "save",
                        1.0);
        verifyFeedbackPushed("save");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_commentCreated_recordsUserEventAndPushesCommentFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(CommentEventTypes.COMMENT_CREATED_V1));

        consumer.consume(message, channel);

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_COMMENT,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "comment",
                        1.0);
        verifyFeedbackPushed("comment");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_postViewed_recordsUserEventAndPushesReadFeedback() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(PostEventTypes.POST_VIEWED_V1));

        consumer.consume(message, channel);

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_VIEW,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "read",
                        1.0);
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

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_SHARE,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "share",
                        1.0);
        verifyFeedbackPushed("share");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_commentLiked_attributesWeakLikeToParentPost() throws Exception {
        stubProcessOnce();
        Message message = message(envelope(CommentEventTypes.COMMENT_LIKED_V1));

        consumer.consume(message, channel);

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.COMMENT_LIKE,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "like",
                        0.5);
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
    void consume_actorNoLongerExists_nacksOnceWithoutRetryAndWritesNothing() throws Exception {
        // ClickHouse has no foreign key, so the consumer checks the actor itself. A stale event
        // whose actor was deleted can never succeed, so it must dead-letter on the very first
        // attempt instead of exhausting the retry budget on every redelivery.
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        stubProcessOnce();
        when(userSummaryService.exists(USER_ID)).thenReturn(false);

        consumer.consume(message, channel);

        verify(userEventAnalyticsRepository, never())
                .insertEngagement(any(), any(), any(), any(), any(), any(), any(), anyDouble());
        verify(gorseClient, never()).insertFeedback(anyList());
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
        assertThat(sleptMillis).isEmpty();
    }

    @Test
    void consume_clickHouseUnavailable_requeuesWithoutRetryOrGorseCall() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        stubProcessOnce();
        doThrow(new ClickHouseUnavailableException(Reason.CIRCUIT_OPEN, "circuit open"))
                .when(userEventAnalyticsRepository)
                .insertEngagement(any(), any(), any(), any(), any(), any(), any(), anyDouble());

        consumer.consume(message, channel);

        // requeue=true: the message waits in the queue while the ingestion controller pauses this
        // listener, instead of being retried in process or dead-lettered.
        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(1L, false);
        verify(userEventAnalyticsRepository, times(1))
                .insertEngagement(any(), any(), any(), any(), any(), any(), any(), anyDouble());
        verify(gorseClient, never()).insertFeedback(anyList());
        assertThat(sleptMillis).isEmpty();
    }

    @Test
    void consume_clickHouseRejectsTheRequest_nacksWithoutRequeueOrRetry() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        stubProcessOnce();
        doThrow(new ClickHouseRequestRejectedException("bad enum", 691, null))
                .when(userEventAnalyticsRepository)
                .insertEngagement(any(), any(), any(), any(), any(), any(), any(), anyDouble());

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, false);
        verify(userEventAnalyticsRepository, times(1))
                .insertEngagement(any(), any(), any(), any(), any(), any(), any(), anyDouble());
        verify(gorseClient, never()).insertFeedback(anyList());
        assertThat(sleptMillis).isEmpty();
    }

    @Test
    void consume_writesClickHouseBeforeGorse_soGorseNeverHoldsFeedbackWithoutARecord()
            throws Exception {
        stubProcessOnce();

        consumer.consume(message(envelope(PostEventTypes.POST_LIKED_V1)), channel);

        InOrder order = inOrder(userEventAnalyticsRepository, gorseClient);
        order.verify(userEventAnalyticsRepository)
                .insertEngagement(
                        eq(EVENT_ID),
                        eq(USER_ID),
                        eq(UserEventType.POST_LIKE),
                        eq("post"),
                        eq(POST_ID),
                        eq(OCCURRED_AT),
                        eq("like"),
                        eq(1.0));
        order.verify(gorseClient).insertFeedback(anyList());
    }

    @Test
    void consume_dwell_isStoredWithTheEventAsTheValueSentToGorse() throws Exception {
        stubProcessOnce();
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        EVENT_ID,
                        PostEventTypes.POST_VIEWED_V1,
                        OCCURRED_AT,
                        USER_ID,
                        "post",
                        POST_ID,
                        Map.of("postId", POST_ID.toString(), "dwellSeconds", 4.5));

        consumer.consume(message(envelope), channel);

        verify(userEventAnalyticsRepository)
                .insertEngagement(
                        EVENT_ID,
                        USER_ID,
                        UserEventType.POST_VIEW,
                        "post",
                        POST_ID,
                        OCCURRED_AT,
                        "read",
                        4.5);
    }

    @Test
    void isTransient_clickHouseFailure_isNeverRetriedInProcess() {
        assertThat(consumer.isTransient(new ClickHouseUnavailableException(Reason.SERVER, "down")))
                .isFalse();
        assertThat(consumer.isTransient(new ClickHouseRequestRejectedException("bad", 62, null)))
                .isFalse();
    }

    @Test
    void consume_duplicateDelivery_skipsHandlerAndAcks() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_LIKED_V1));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenReturn(ProcessedMessageResult.DUPLICATE);

        consumer.consume(message, channel);

        verify(userEventAnalyticsRepository, never())
                .insertEngagement(any(), any(), any(), any(), any(), any(), any(), anyDouble());
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
