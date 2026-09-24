package com.app.common.seed.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.comment.consumer.CommentNotificationConsumer;
import com.app.modules.post.consumer.PostNotificationConsumer;

import lombok.RequiredArgsConstructor;

/**
 * Enqueues {@link SeedOutboxEmitter}'s five event kinds through the real {@link OutboxService}, one
 * batch per transaction.
 *
 * <p>A replayed like or comment is also recorded as already handled by the consumer that would turn
 * it into a notification, in the same transaction. {@code NotificationSeedWriter} has written those
 * notifications with their historical timestamps, and the seed profile only holds the consumers off
 * for the run itself: without the inbox rows the queued replay would be drained on the next
 * ordinary start and every seeded comment and like notified a second time, stamped with that day.
 * Consumers skip an event their inbox already holds, so the other consumers of the same events -
 * search indexing and recommendation feedback - still receive and act on them.
 *
 * <p>Deliberately a separate bean from {@link SeedOutboxEmitter}: {@code OutboxService.enqueue}
 * requires {@code Propagation.MANDATORY}, and this class's {@code @Transactional} batch methods are
 * what open that transaction. If the batching loop lived on {@code SeedOutboxEmitter} itself and
 * called a {@code @Transactional} method on {@code this}, that self-invocation would bypass
 * Spring's proxy and silently run with no transaction, causing {@code OutboxService.enqueue} to
 * fail its {@code MANDATORY} propagation check.
 */
@Component
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class SeedOutboxBatchWriter {

    private static final String AGGREGATE_TYPE_POST = "post";
    private static final String AGGREGATE_TYPE_HASHTAG = "hashtag";
    private static final String AGGREGATE_TYPE_COMMENT = "comment";

    private static final String POST_INDEX_UPSERT_V1 = "post.index.upsert.v1";
    private static final String HASHTAG_INDEX_UPSERT_V1 = "hashtag.index.upsert.v1";
    private static final String POST_LIKED_V1 = "post.liked.v1";
    private static final String POST_SAVED_V1 = "post.saved.v1";
    private static final String POST_VIEWED_V1 = "post.viewed.v1";
    private static final String POST_SHARED_V1 = "post.shared.v1";
    private static final String COMMENT_CREATED_V1 = "comment.created.v1";

    private final OutboxService outboxService;
    private final JdbcTemplate jdbc;

    /** Emits exactly one event of each of the five kinds, in a single transaction. */
    @Transactional
    void emitProof(SeedOutboxEmitter.ProofSample sample) {
        enqueuePostIndex(sample.post());
        enqueueHashtagIndex(sample.hashtagId());
        enqueueLike(sample.like());
        enqueueSave(sample.save());
        enqueueComment(sample.comment());
    }

    @Transactional
    void emitPostIndexBatch(List<SeedOutboxEmitter.PostIndexRow> batch) {
        batch.forEach(this::enqueuePostIndex);
    }

    @Transactional
    void emitHashtagIndexBatch(List<UUID> batch) {
        batch.forEach(this::enqueueHashtagIndex);
    }

    @Transactional
    void emitLikeBatch(List<SeedOutboxEmitter.ReactionRow> batch) {
        batch.forEach(this::enqueueLike);
    }

    @Transactional
    void emitSaveBatch(List<SeedOutboxEmitter.ReactionRow> batch) {
        batch.forEach(this::enqueueSave);
    }

    @Transactional
    void emitCommentBatch(List<SeedOutboxEmitter.CommentRow> batch) {
        batch.forEach(this::enqueueComment);
    }

    @Transactional
    void emitViewBatch(List<SeedOutboxEmitter.ReactionRow> batch) {
        batch.forEach(this::enqueueView);
    }

    @Transactional
    void emitShareBatch(List<SeedOutboxEmitter.ShareRow> batch) {
        batch.forEach(this::enqueueShare);
    }

    // Payload shape mirrors MessageServiceImpl's own post.shared.v1 enqueue: the aggregate is the
    // shared post, not the message that carried it.
    private void enqueueShare(SeedOutboxEmitter.ShareRow row) {
        outboxService.enqueue(
                POST_SHARED_V1,
                POST_SHARED_V1,
                AGGREGATE_TYPE_POST,
                row.postId(),
                row.senderId(),
                Map.of(
                        "postId", row.postId().toString(),
                        "messageId", row.messageId().toString()));
    }

    // Payload shape mirrors PostViewServiceImpl's enqueue call exactly. No dwellSeconds key: a
    // seeded view is not an impression measured in a viewport, and the consumer falls back to the
    // unit feedback value when the key is absent.
    private void enqueueView(SeedOutboxEmitter.ReactionRow row) {
        outboxService.enqueue(
                POST_VIEWED_V1,
                POST_VIEWED_V1,
                AGGREGATE_TYPE_POST,
                row.postId(),
                row.userId(),
                Map.of(
                        "postId", row.postId().toString(),
                        "postOwnerId", row.postOwnerId().toString(),
                        "userId", row.userId().toString()));
    }

    // Payload shape mirrors PostServiceImpl.enqueuePostIndexUpsert exactly: the consumer re-derives
    // caption and status from the source-of-truth post row, but PostIndexUpsertEvent still requires
    // userId and hashtagIds to build the Elasticsearch document.
    private void enqueuePostIndex(SeedOutboxEmitter.PostIndexRow row) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", row.postId().toString());
        data.put("userId", row.userId().toString());
        data.put("status", "published");
        data.put("hashtagIds", row.hashtagIds());
        data.put("createdAt", row.createdAt().toString());
        outboxService.enqueue(
                POST_INDEX_UPSERT_V1,
                POST_INDEX_UPSERT_V1,
                AGGREGATE_TYPE_POST,
                row.postId(),
                row.userId(),
                data);
    }

    // Payload shape mirrors HashtagIndexEventPublisher.enqueueSync: only the id travels through the
    // event, the consumer reads name/post_count/status from the source-of-truth hashtag row. No
    // actor caused this seed-time event, matching the production system-event convention of a null
    // actorId.
    private void enqueueHashtagIndex(UUID hashtagId) {
        outboxService.enqueue(
                HASHTAG_INDEX_UPSERT_V1,
                HASHTAG_INDEX_UPSERT_V1,
                AGGREGATE_TYPE_HASHTAG,
                hashtagId,
                null,
                Map.of("hashtagId", hashtagId.toString()));
    }

    // Payload shape mirrors PostLikeServiceImpl's enqueue call.
    private void enqueueLike(SeedOutboxEmitter.ReactionRow row) {
        OutboxEvent event =
                outboxService.enqueue(
                        POST_LIKED_V1,
                        POST_LIKED_V1,
                        AGGREGATE_TYPE_POST,
                        row.postId(),
                        row.userId(),
                        Map.of(
                                "postId", row.postId().toString(),
                                "postOwnerId", row.postOwnerId().toString(),
                                "userId", row.userId().toString()));
        markHandled(PostNotificationConsumer.CONSUMER_NAME, event);
    }

    // Payload shape mirrors PostSaveServiceImpl's enqueue call.
    private void enqueueSave(SeedOutboxEmitter.ReactionRow row) {
        outboxService.enqueue(
                POST_SAVED_V1,
                POST_SAVED_V1,
                AGGREGATE_TYPE_POST,
                row.postId(),
                row.userId(),
                Map.of(
                        "postId", row.postId().toString(),
                        "postOwnerId", row.postOwnerId().toString(),
                        "userId", row.userId().toString()));
    }

    // Payload shape mirrors CommentServiceImpl.enqueueCreated: mentionedUserIds is always present
    // (empty here - the seed pipeline has no persisted mention record to reconstruct), and
    // parentOwnerId is omitted rather than null for a root comment, matching that method's own
    // null-value-is-omitted convention (OutboxService stores data via Map.copyOf, which rejects
    // null
    // values).
    private void enqueueComment(SeedOutboxEmitter.CommentRow row) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", row.postId().toString());
        data.put("postOwnerId", row.postOwnerId().toString());
        data.put("commentId", row.commentId().toString());
        data.put("userId", row.userId().toString());
        data.put("commentOwnerId", row.userId().toString());
        data.put("depth", row.depth());
        data.put("mentionedUserIds", List.of());
        if (row.parentOwnerId() != null) {
            data.put("parentOwnerId", row.parentOwnerId().toString());
        }
        OutboxEvent event =
                outboxService.enqueue(
                        COMMENT_CREATED_V1,
                        COMMENT_CREATED_V1,
                        AGGREGATE_TYPE_COMMENT,
                        row.commentId(),
                        row.userId(),
                        data);
        markHandled(CommentNotificationConsumer.CONSUMER_NAME, event);
    }

    // The same row ProcessedMessageService writes when a consumer handles an event, so the
    // consumer's own duplicate check skips the replay.
    private void markHandled(String consumerName, OutboxEvent event) {
        jdbc.update(
                "INSERT INTO processed_messages (consumer_name, event_id, event_type)"
                        + " VALUES (?, ?, ?) ON CONFLICT (consumer_name, event_id) DO NOTHING",
                consumerName,
                event.getEventId(),
                event.getEventType());
    }
}
