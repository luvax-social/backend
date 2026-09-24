package com.app.modules.post.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
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
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.post.entity.PostLikeId;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostLikeRepository;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class PostNotificationConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LIKER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID POST = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private NotificationService notificationService;
    @Mock private PostLikeRepository postLikeRepository;
    @Mock private Channel channel;

    private PostNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        ConsumerRetryProperties retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(1);
        retryProperties.setRetryBackoffs(List.of(Duration.ZERO));
        consumer =
                new PostNotificationConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        notificationService,
                        retryProperties,
                        postLikeRepository,
                        millis -> {});
    }

    @Test
    void consume_likeStillInPlace_createsTheLikeNotification() throws Exception {
        runHandlers();
        when(postLikeRepository.existsById(new PostLikeId(LIKER, POST))).thenReturn(true);

        consumer.consume(message(envelope(PostEventTypes.POST_LIKED_V1, data())), channel);

        verify(notificationService)
                .create(LIKER, OWNER, NotificationType.LIKE_POST, "post", POST, POST);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_likeWithdrawnBeforeConsumption_writesNothing() throws Exception {
        runHandlers();
        when(postLikeRepository.existsById(new PostLikeId(LIKER, POST))).thenReturn(false);

        consumer.consume(message(envelope(PostEventTypes.POST_LIKED_V1, data())), channel);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_unlike_retractsTheLiker() throws Exception {
        runHandlers();
        when(postLikeRepository.existsById(new PostLikeId(LIKER, POST))).thenReturn(false);

        consumer.consume(message(envelope(PostEventTypes.POST_UNLIKED_V1, data())), channel);

        verify(notificationService).retract(LIKER, OWNER, NotificationType.LIKE_POST, POST);
    }

    @Test
    void consume_unlikeFollowedByAReLike_retractsNothing() throws Exception {
        runHandlers();
        when(postLikeRepository.existsById(new PostLikeId(LIKER, POST))).thenReturn(true);

        consumer.consume(message(envelope(PostEventTypes.POST_UNLIKED_V1, data())), channel);

        verify(notificationService, never()).retract(any(), any(), any(), any());
    }

    @Test
    void consume_duplicateDelivery_skipsTheHandler() throws Exception {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenReturn(ProcessedMessageResult.DUPLICATE);

        consumer.consume(message(envelope(PostEventTypes.POST_LIKED_V1, data())), channel);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_missingLiker_deadLettersWithoutRequeue() throws Exception {
        consumer.consume(
                message(
                        envelope(
                                PostEventTypes.POST_LIKED_V1,
                                Map.of(
                                        "postId",
                                        POST.toString(),
                                        "postOwnerId",
                                        OWNER.toString()))),
                channel);

        verify(channel).basicNack(1L, false, false);
        verify(processedMessageService, never()).processOnce(any(), any(), any(), any());
    }

    @Test
    void consume_transientFailureExhaustsRetry_deadLettersWithoutRequeue() throws Exception {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db down"));

        consumer.consume(message(envelope(PostEventTypes.POST_LIKED_V1, data())), channel);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private void runHandlers() {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
    }

    private static Map<String, Object> data() {
        return Map.of(
                "postId", POST.toString(),
                "postOwnerId", OWNER.toString(),
                "userId", LIKER.toString());
    }

    private static DomainEventEnvelope envelope(String type, Map<String, Object> data) {
        return new DomainEventEnvelope(
                EVENT_ID, type, OffsetDateTime.now(ZoneOffset.UTC), LIKER, "post", POST, data);
    }

    private static Message message(DomainEventEnvelope envelope) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(1L)
                .build();
    }
}
