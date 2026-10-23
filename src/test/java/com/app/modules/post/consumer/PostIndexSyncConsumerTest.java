package com.app.modules.post.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DeadLetterPublisher;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.event.PostIndexUpsertEvent;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.search.PostDocumentFactory;
import com.app.modules.post.search.PostSearchRepository;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PostIndexSyncConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private DeadLetterPublisher deadLetterPublisher;
    @Mock private PostSearchRepository postSearchRepository;
    @Mock private PostRepository postRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private HashtagService hashtagService;
    @Mock private PostDocumentFactory postDocumentFactory;
    @Mock private GorseClient gorseClient;
    @Mock private Channel channel;

    private ConsumerRetryProperties retryProperties;
    private List<Long> sleptMillis;
    private PostIndexSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setRetryBackoffs(List.of(Duration.ofMillis(5), Duration.ZERO));
        sleptMillis = new ArrayList<>();
        consumer =
                new PostIndexSyncConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        deadLetterPublisher,
                        retryProperties,
                        postSearchRepository,
                        postRepository,
                        hashtagService,
                        postDocumentFactory,
                        gorseClient,
                        objectMapper,
                        sleptMillis::add);
    }

    @Test
    void isTransient_dataAccessException_returnsTrue() {
        assertThat(consumer.isTransient(new QueryTimeoutException("timeout"))).isTrue();
    }

    @Test
    void isTransient_redisSystemException_returnsTrue() {
        assertThat(consumer.isTransient(new RedisSystemException("redis down", null))).isTrue();
    }

    @Test
    void isTransient_amqpException_returnsTrue() {
        assertThat(consumer.isTransient(new AmqpException("broker down"))).isTrue();
    }

    @Test
    void isTransient_appExceptionServiceUnavailable_returnsTrue() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.SERVICE_UNAVAILABLE)))
                .isTrue();
    }

    @Test
    void isTransient_appExceptionOtherCode_returnsFalse() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.POST_NOT_FOUND))).isFalse();
    }

    @Test
    void isTransient_unknownRuntimeException_returnsTrue() {
        assertThat(consumer.isTransient(new IllegalStateException("unexpected"))).isTrue();
    }

    @Test
    void consume_nullEventId_routesToDlqAndAcks() throws Exception {
        Message message = message(nullEventIdEnvelope());

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        eq(message),
                        eq(RabbitMqTopologyConfig.POST_INDEX_DEAD_LETTER_ROUTING_KEY),
                        any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_blankEventType_routesToDlqAndAcks() throws Exception {
        Message message = message(blankEventTypeEnvelope());

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        eq(message),
                        eq(RabbitMqTopologyConfig.POST_INDEX_DEAD_LETTER_ROUTING_KEY),
                        any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_unknownEventType_routesToDlqAndAcks() throws Exception {
        Message message = message(envelope("unknown.event.type"));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        eq(message),
                        eq(RabbitMqTopologyConfig.POST_INDEX_DEAD_LETTER_ROUTING_KEY),
                        any());
        verify(channel).basicAck(1L, false);
        verify(postSearchRepository, never()).save(any());
    }

    @Test
    void consume_upsertPostAbsent_skipsIndexAndAcks() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_INDEX_UPSERT_V1));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
        when(objectMapper.convertValue(any(), eq(PostIndexUpsertEvent.class)))
                .thenReturn(upsertEvent());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        consumer.consume(message, channel);

        verify(postSearchRepository, never()).save(any());
        // A post that is gone or soft-deleted must be hidden in the recommender, not merely
        // skipped: auto_insert_item would otherwise recreate it unhidden from feedback alone.
        // Hidden by upsert, not by hideItem, because hideItem stores nothing for an id Gorse has
        // not seen yet while still reporting success.
        ArgumentCaptor<List<GorseItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).upsertItems(captor.capture());
        assertThat(captor.getValue().get(0).hidden()).isTrue();
        verify(gorseClient, never()).hideItem(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_upsertPostNotPublished_skipsIndexAndAcks() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_INDEX_UPSERT_V1));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
        when(objectMapper.convertValue(any(), eq(PostIndexUpsertEvent.class)))
                .thenReturn(upsertEvent());
        Post draftPost = Post.builder().status(PostStatus.DRAFT).build();
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(draftPost));

        consumer.consume(message, channel);

        verify(postSearchRepository, never()).save(any());
        ArgumentCaptor<List<GorseItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).upsertItems(captor.capture());
        assertThat(captor.getValue().get(0).hidden()).isTrue();
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_upsertPublishedPost_upsertsRecommenderItemWithHashtagNames() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_INDEX_UPSERT_V1));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
        when(objectMapper.convertValue(any(), eq(PostIndexUpsertEvent.class)))
                .thenReturn(upsertEvent());
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        Post publishedPost =
                Post.builder().status(PostStatus.PUBLISHED).createdAt(createdAt).build();
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(publishedPost));
        when(hashtagService.getHashtagNamesForPost(POST_ID))
                .thenReturn(List.of("sunset", "travel"));

        consumer.consume(message, channel);

        ArgumentCaptor<List<GorseItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).upsertItems(captor.capture());
        GorseItem item = captor.getValue().get(0);
        assertThat(item.itemId()).isEqualTo(POST_ID.toString());
        assertThat(item.labels()).containsExactly("sunset", "travel");
        assertThat(item.hidden()).isFalse();
        assertThat(item.timestamp()).isEqualTo(createdAt);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_dlqPublishFailure_nacksOriginal() throws Exception {
        Message message = message(nullEventIdEnvelope());
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetterPublisher)
                .publish(any(), any(), any());

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_ackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(envelope(PostEventTypes.POST_INDEX_UPSERT_V1));
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
        when(objectMapper.convertValue(any(), eq(PostIndexUpsertEvent.class)))
                .thenReturn(upsertEvent());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());
        doThrow(new IOException("channel closed")).when(channel).basicAck(1L, false);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void consume_nackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(nullEventIdEnvelope());
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetterPublisher)
                .publish(any(), any(), any());
        doThrow(new IOException("channel closed")).when(channel).basicNack(1L, false, true);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
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
                OffsetDateTime.now(ZoneOffset.UTC),
                USER_ID,
                "post",
                POST_ID,
                Map.of("postId", POST_ID.toString(), "userId", USER_ID.toString()));
    }

    private DomainEventEnvelope nullEventIdEnvelope() {
        return new DomainEventEnvelope(
                null,
                PostEventTypes.POST_INDEX_UPSERT_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                USER_ID,
                "post",
                POST_ID,
                Map.of());
    }

    private DomainEventEnvelope blankEventTypeEnvelope() {
        return new DomainEventEnvelope(
                EVENT_ID,
                "",
                OffsetDateTime.now(ZoneOffset.UTC),
                USER_ID,
                "post",
                POST_ID,
                Map.of());
    }

    private PostIndexUpsertEvent upsertEvent() {
        return new PostIndexUpsertEvent(
                EVENT_ID,
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                POST_ID,
                USER_ID,
                "published",
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC),
                null);
    }
}
