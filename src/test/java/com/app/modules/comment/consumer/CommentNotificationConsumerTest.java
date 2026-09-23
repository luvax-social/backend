package com.app.modules.comment.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;

import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.comment.repository.CommentLikeRepository;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class CommentNotificationConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID POST_OWNER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private NotificationService notificationService;
    @Mock private CommentLikeRepository commentLikeRepository;
    @Mock private Channel channel;

    private CommentNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        // maxAttempts=1 with a zero backoff means any transient failure "exhausts" retry on the
        // first attempt without a real Thread.sleep, keeping this test fast and deterministic.
        ConsumerRetryProperties retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(1);
        retryProperties.setRetryBackoffs(List.of(Duration.ZERO));
        consumer =
                new CommentNotificationConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        notificationService,
                        retryProperties,
                        commentLikeRepository);
    }

    @Test
    void consume_topLevelCommentCreated_createsCommentPostNotification() throws Exception {
        Message message = message(envelope());
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });

        consumer.consume(message, channel);

        verify(notificationService)
                .create(
                        ACTOR_ID,
                        POST_OWNER_ID,
                        NotificationType.COMMENT_POST,
                        "comment",
                        COMMENT_ID,
                        POST_ID);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void consume_duplicateEvent_skipsHandlerAndAcks() throws Exception {
        Message message = message(envelope());
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenReturn(ProcessedMessageResult.DUPLICATE);

        consumer.consume(message, channel);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_missingCommentId_nacksWithoutRequeue() throws Exception {
        Message message =
                message(
                        new DomainEventEnvelope(
                                EVENT_ID,
                                CommentEventTypes.COMMENT_CREATED_V1,
                                OffsetDateTime.now(ZoneOffset.UTC),
                                ACTOR_ID,
                                "comment",
                                COMMENT_ID,
                                Map.of("postOwnerId", POST_OWNER_ID.toString(), "depth", 0)));

        consumer.consume(message, channel);

        // false requeue lets the broker route the message per the queue's declared
        // dead-letter-exchange/routing-key topology instead of redelivering it.
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void consume_transientFailureExhaustsRetry_nacksWithoutRequeue() throws Exception {
        Message message = message(envelope());
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db down"));

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_commentMentioningThePostOwner_notifiesThemOnce() throws Exception {
        UUID other = UUID.randomUUID();
        runHandlers();
        when(notificationService.create(
                        ACTOR_ID,
                        POST_OWNER_ID,
                        NotificationType.COMMENT_POST,
                        "comment",
                        COMMENT_ID,
                        POST_ID))
                .thenReturn(true);

        consumer.consume(
                message(
                        created(
                                List.of(
                                        POST_OWNER_ID.toString(),
                                        other.toString(),
                                        other.toString()))),
                channel);

        verify(notificationService, never())
                .create(
                        ACTOR_ID,
                        POST_OWNER_ID,
                        NotificationType.MENTION_COMMENT,
                        "comment",
                        COMMENT_ID,
                        POST_ID);
        verify(notificationService, times(1))
                .create(
                        ACTOR_ID,
                        other,
                        NotificationType.MENTION_COMMENT,
                        "comment",
                        COMMENT_ID,
                        POST_ID);
    }

    @Test
    void consume_postOwnerSuppressedTheCommentNotification_stillGetsTheMention() throws Exception {
        runHandlers();

        consumer.consume(message(created(List.of(POST_OWNER_ID.toString()))), channel);

        verify(notificationService)
                .create(
                        ACTOR_ID,
                        POST_OWNER_ID,
                        NotificationType.MENTION_COMMENT,
                        "comment",
                        COMMENT_ID,
                        POST_ID);
    }

    @Test
    void consume_likeStillInPlace_createsTheLikeNotification() throws Exception {
        runHandlers();
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(ACTOR_ID, COMMENT_ID))
                .thenReturn(true);

        consumer.consume(message(like(CommentEventTypes.COMMENT_LIKED_V1)), channel);

        verify(notificationService)
                .create(
                        ACTOR_ID,
                        POST_OWNER_ID,
                        NotificationType.LIKE_COMMENT,
                        "comment",
                        COMMENT_ID,
                        POST_ID);
    }

    @Test
    void consume_likeAlreadyWithdrawn_writesNothing() throws Exception {
        runHandlers();
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(ACTOR_ID, COMMENT_ID))
                .thenReturn(false);

        consumer.consume(message(like(CommentEventTypes.COMMENT_LIKED_V1)), channel);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_unlike_retractsTheLiker() throws Exception {
        runHandlers();
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(ACTOR_ID, COMMENT_ID))
                .thenReturn(false);

        consumer.consume(message(like(CommentEventTypes.COMMENT_UNLIKED_V1)), channel);

        verify(notificationService)
                .retract(ACTOR_ID, POST_OWNER_ID, NotificationType.LIKE_COMMENT, COMMENT_ID);
    }

    @Test
    void consume_unlikeFollowedByAReLike_retractsNothing() throws Exception {
        runHandlers();
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(ACTOR_ID, COMMENT_ID))
                .thenReturn(true);

        consumer.consume(message(like(CommentEventTypes.COMMENT_UNLIKED_V1)), channel);

        verify(notificationService, never()).retract(any(), any(), any(), any());
    }

    private void runHandlers() {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
    }

    private DomainEventEnvelope created(List<String> mentionedUserIds) {
        return new DomainEventEnvelope(
                EVENT_ID,
                CommentEventTypes.COMMENT_CREATED_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                ACTOR_ID,
                "comment",
                COMMENT_ID,
                Map.of(
                        "commentId",
                        COMMENT_ID.toString(),
                        "postOwnerId",
                        POST_OWNER_ID.toString(),
                        "postId",
                        POST_ID.toString(),
                        "depth",
                        0,
                        "mentionedUserIds",
                        mentionedUserIds));
    }

    private DomainEventEnvelope like(String eventType) {
        return new DomainEventEnvelope(
                EVENT_ID,
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                ACTOR_ID,
                "comment",
                COMMENT_ID,
                Map.of(
                        "commentId",
                        COMMENT_ID.toString(),
                        "commentOwnerId",
                        POST_OWNER_ID.toString(),
                        "postId",
                        POST_ID.toString()));
    }

    private Message message(DomainEventEnvelope envelope) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(1L)
                .build();
    }

    private DomainEventEnvelope envelope() {
        return new DomainEventEnvelope(
                EVENT_ID,
                CommentEventTypes.COMMENT_CREATED_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                ACTOR_ID,
                "comment",
                COMMENT_ID,
                Map.of(
                        "commentId",
                        COMMENT_ID.toString(),
                        "postOwnerId",
                        POST_OWNER_ID.toString(),
                        "postId",
                        POST_ID.toString(),
                        "depth",
                        0));
    }
}
