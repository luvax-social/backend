package com.app.modules.recommendation.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.messaging.RecommendationEventTypes;
import com.app.modules.recommendation.messaging.UserEventImportedEvent;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.social.messaging.SocialEventTypes;
import com.rabbitmq.client.Channel;

/**
 * How the import consumer routes a delivered event: the imported-event contract is read as it
 * always was, a mapped domain signal is written from the table, and anything else is nacked without
 * a requeue so it dead-letters.
 */
@ExtendWith(MockitoExtension.class)
class UserEventImportConsumerTest {

    private static final long DELIVERY_TAG = 7L;
    private static final OffsetDateTime OCCURRED_AT =
            OffsetDateTime.of(2026, 8, 15, 9, 30, 0, 0, ZoneOffset.UTC);

    @Mock private DomainEventMessageParser parser;
    @Mock private ProcessedMessageService processedMessageService;
    @Mock private UserEventAnalyticsRepository analyticsRepository;
    @Mock private Channel channel;

    private UserEventImportConsumer consumer;
    private final Message message = new Message(new byte[0], deliveryProperties());
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer =
                new UserEventImportConsumer(parser, processedMessageService, analyticsRepository);
        // The inbox runs the handler on first delivery; the wrapper stands in for that.
        org.mockito.Mockito.lenient()
                .when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Runnable handler = invocation.getArgument(3);
                            handler.run();
                            return ProcessedMessageResult.PROCESSED;
                        });
    }

    @Test
    void consume_mappedDomainSignal_writesTheMappedRowAndAcks() throws IOException {
        UUID eventId = UUID.randomUUID();
        when(parser.parse(message))
                .thenReturn(
                        new DomainEventEnvelope(
                                eventId,
                                SocialEventTypes.USER_MUTED_V1,
                                OCCURRED_AT,
                                actor,
                                "user",
                                actor,
                                Map.of(
                                        "targetUserId",
                                        target.toString(),
                                        "mutePosts",
                                        false,
                                        "muteStories",
                                        true)));

        consumer.consume(message, channel);

        verify(analyticsRepository)
                .insertImported(
                        eventId,
                        actor,
                        UserEventType.ACCOUNT_MUTE,
                        "user",
                        target,
                        "{\"posts\":false,\"stories\":true}",
                        OCCURRED_AT);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consume_hiddenComment_writesTheCommenterAsTheActor() throws IOException {
        UUID eventId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(parser.parse(message))
                .thenReturn(
                        new DomainEventEnvelope(
                                eventId,
                                CommentEventTypes.COMMENT_HIDDEN_V1,
                                OCCURRED_AT,
                                actor,
                                "comment",
                                commentId,
                                Map.of(
                                        "postId",
                                        UUID.randomUUID().toString(),
                                        "commentId",
                                        commentId.toString(),
                                        "reason",
                                        "hidden_words")));

        consumer.consume(message, channel);

        verify(analyticsRepository)
                .insertImported(
                        eventId,
                        actor,
                        UserEventType.COMMENT_HIDDEN,
                        "comment",
                        commentId,
                        "{\"reason\":\"hidden_words\"}",
                        OCCURRED_AT);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consume_importedEvent_isStillReadFromItsOwnPayloadWithTheAggregateAsTheAccount()
            throws IOException {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-15T09:30:00Z");
        when(parser.parse(message))
                .thenReturn(
                        new DomainEventEnvelope(
                                eventId,
                                RecommendationEventTypes.USER_EVENT_IMPORTED_V1,
                                OCCURRED_AT.plusDays(1),
                                null,
                                UserEventImportedEvent.AGGREGATE_TYPE,
                                actor,
                                UserEventImportedEvent.payload(
                                        UserEventType.PROFILE_VIEW,
                                        "user",
                                        target,
                                        null,
                                        createdAt)));

        consumer.consume(message, channel);

        verify(analyticsRepository)
                .insertImported(
                        eventId,
                        actor,
                        UserEventType.PROFILE_VIEW,
                        "user",
                        target,
                        "",
                        createdAt.atOffset(ZoneOffset.UTC));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consume_eventTypeNobodyMapped_isNackedWithoutRequeue() throws IOException {
        when(parser.parse(message))
                .thenReturn(
                        new DomainEventEnvelope(
                                UUID.randomUUID(),
                                "post.liked.v1",
                                OCCURRED_AT,
                                actor,
                                "post",
                                UUID.randomUUID(),
                                Map.of()));

        consumer.consume(message, channel);

        verify(analyticsRepository, never())
                .insertImported(any(), any(), any(), any(), any(), any(), any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void consume_mappedEventWithoutAnActor_isNackedWithoutRequeue() throws IOException {
        when(parser.parse(message))
                .thenReturn(
                        new DomainEventEnvelope(
                                UUID.randomUUID(),
                                SocialEventTypes.USER_UNMUTED_V1,
                                OCCURRED_AT,
                                null,
                                "user",
                                actor,
                                Map.of("targetUserId", target.toString())));

        consumer.consume(message, channel);

        verify(analyticsRepository, never())
                .insertImported(any(), any(), any(), any(), any(), any(), any());
        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    private static MessageProperties deliveryProperties() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return properties;
    }
}
