package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.messaging.PostEventTypes;

/**
 * Enqueues the search-index and recommender events of a post.
 *
 * <p>Every write path that changes what the index or the recommender holds for a post goes through
 * here, so the payload is built in one place. The payload carries identifiers and times only; the
 * consumer reads the caption, the place and the co-authors from the source-of-truth rows, so user
 * text never travels through the outbox and a late event cannot restore a stale author list.
 */
@Component
public class PostIndexEventPublisher {

    private final OutboxService outboxService;
    private final HashtagService hashtagService;

    public PostIndexEventPublisher(OutboxService outboxService, HashtagService hashtagService) {
        this.outboxService = outboxService;
        this.hashtagService = hashtagService;
    }

    /**
     * Enqueues an upsert of the post's document and recommender item.
     *
     * <p>Must run after the hashtag associations were written, so they are queryable here.
     *
     * @param post the post, with its generated timestamps populated
     */
    public void enqueueUpsert(Post post) {
        enqueueUpsert(post.getId(), post.getUserId(), post.getCreatedAt(), post.getPublishedAt());
    }

    /**
     * Enqueues an upsert from the identifiers and times alone.
     *
     * @param postId the post
     * @param userId the post owner
     * @param createdAt when the post row was created
     * @param publishedAt when the post first became published, or null
     */
    public void enqueueUpsert(
            UUID postId, UUID userId, OffsetDateTime createdAt, OffsetDateTime publishedAt) {
        List<String> hashtagIds =
                hashtagService
                        .getHashtagIdsForPosts(List.of(postId))
                        .getOrDefault(postId, List.of())
                        .stream()
                        .map(UUID::toString)
                        .toList();
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId.toString());
        data.put("userId", userId.toString());
        data.put("status", "published");
        data.put("hashtagIds", hashtagIds);
        data.put("createdAt", createdAt.toString());
        if (publishedAt != null) {
            data.put("publishedAt", publishedAt.toString());
        }
        outboxService.enqueue(
                PostEventTypes.POST_INDEX_UPSERT_V1,
                PostEventTypes.POST_INDEX_UPSERT_V1,
                "post",
                postId,
                userId,
                data);
    }

    /**
     * Enqueues removal of the post's document and hides its recommender item.
     *
     * @param postId the post
     * @param userId the post owner
     */
    public void enqueueDelete(UUID postId, UUID userId) {
        outboxService.enqueue(
                PostEventTypes.POST_INDEX_DELETE_V1,
                PostEventTypes.POST_INDEX_DELETE_V1,
                "post",
                postId,
                userId,
                Map.of("postId", postId.toString()));
    }
}
