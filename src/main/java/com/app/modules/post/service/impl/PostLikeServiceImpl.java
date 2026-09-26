package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserListItemResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.post.dto.response.LikeActionResponse;
import com.app.modules.post.dto.response.LikedPostResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostLike;
import com.app.modules.post.entity.PostLikeId;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostLikeRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostLikeService;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.service.UserSummaryService;

@Service
public class PostLikeServiceImpl implements PostLikeService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String AGGREGATE_TYPE = "post";

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;
    private final UserSummaryService userSummaryService;
    private final SocialService socialService;
    private final OutboxService outboxService;

    public PostLikeServiceImpl(
            PostRepository postRepository,
            PostLikeRepository postLikeRepository,
            PostVisibilityService postVisibilityService,
            PostResponseAssembler postResponseAssembler,
            UserSummaryService userSummaryService,
            SocialService socialService,
            OutboxService outboxService) {
        this.postRepository = postRepository;
        this.postLikeRepository = postLikeRepository;
        this.postVisibilityService = postVisibilityService;
        this.postResponseAssembler = postResponseAssembler;
        this.userSummaryService = userSummaryService;
        this.socialService = socialService;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public LikeActionResponse likePost(UUID userId, UUID postId) {
        Post post = fetchVisiblePublishedPost(userId, postId);
        PostLikeId likeId = new PostLikeId(userId, postId);
        if (postLikeRepository.existsById(likeId)) {
            throw new AppException(ApiErrorCode.POST_ALREADY_LIKED);
        }
        // Flush forces the INSERT (and its AFTER INSERT counter trigger) before the scalar
        // re-read; the entity in the persistence context still carries the stale counter.
        try {
            postLikeRepository.saveAndFlush(PostLike.builder().id(likeId).build());
        } catch (DataIntegrityViolationException ex) {
            // A concurrent double-submit lost the insert race; the (user_id, post_id) primary key
            // already recorded the like, so surface the same clean conflict rather than a 500.
            throw new AppException(ApiErrorCode.POST_ALREADY_LIKED);
        }
        outboxService.enqueue(
                PostEventTypes.POST_LIKED_V1,
                PostEventTypes.POST_LIKED_V1,
                "post",
                postId,
                userId,
                Map.of(
                        "postId", postId.toString(),
                        "postOwnerId", post.getUserId().toString(),
                        "userId", userId.toString()));
        enqueueLiveEvent(PostEventTypes.POST_LIVE_LIKED_V1, post, userId);
        return new LikeActionResponse(postId, true, postRepository.findLikeCount(postId));
    }

    @Override
    @Transactional
    public LikeActionResponse unlikePost(UUID userId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        // Unpublished posts surface as not-found to avoid leaking their existence, and a missing
        // like row collapses onto the same code so it cannot serve as a separate oracle.
        if (post.getStatus() != PostStatus.PUBLISHED && !userId.equals(post.getUserId())) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        // A published post can still be hidden from this viewer by a block, a private owner
        // without an accepted follow, or a soft-deleted owner; apply the same account-level
        // decision likePost uses before mutating the relation.
        if (!postVisibilityService.isVisibleTo(userId, post)) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        // Conditional delete rather than load-then-delete(entity): the latter raises
        // ObjectOptimisticLockingFailureException (-> 500) when a concurrent duplicate request
        // already removed the same row. A missing like row still collapses onto POST_NOT_FOUND,
        // matching the visibility checks above, so it cannot serve as a separate "have you liked
        // this" oracle.
        int deleted = postLikeRepository.deleteByUserAndPost(userId, postId);
        if (deleted == 0) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        outboxService.enqueue(
                PostEventTypes.POST_UNLIKED_V1,
                PostEventTypes.POST_UNLIKED_V1,
                AGGREGATE_TYPE,
                postId,
                userId,
                Map.of(
                        "postId", postId.toString(),
                        "postOwnerId", post.getUserId().toString(),
                        "userId", userId.toString()));
        enqueueLiveEvent(PostEventTypes.POST_LIVE_UNLIKED_V1, post, userId);
        return new LikeActionResponse(postId, false, postRepository.findLikeCount(postId));
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserListItemResponse> listLikers(
            UUID viewerId, UUID postId, String cursor, int size) {
        fetchVisiblePublishedPost(viewerId, postId);
        int pageSize = normalizeLimit(size);
        Cursor decoded = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<PostLike> likes =
                decoded == null
                        ? postLikeRepository.findFirstLikers(postId, viewerId, page)
                        : postLikeRepository.findLikersBefore(
                                postId,
                                viewerId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        boolean hasNextPage = likes.size() > pageSize;
        if (hasNextPage) {
            likes = likes.subList(0, pageSize);
        }
        if (likes.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        List<UUID> likerIds = likes.stream().map(l -> l.getId().getUserId()).toList();
        // Batch-resolve every liker; a soft-deleted liker resolves to a placeholder rather than
        // being dropped, so the page size stays consistent with the like count.
        Map<UUID, UserSummaryResponse> summaries = userSummaryService.loadSummaries(likerIds);
        Map<UUID, ViewerRelationshipResponse> relationships =
                socialService.loadRelationships(viewerId, likerIds);
        List<UserListItemResponse> content =
                likerIds.stream()
                        .map(
                                id ->
                                        new UserListItemResponse(
                                                summaries.get(id),
                                                relationships.getOrDefault(
                                                        id, ViewerRelationshipResponse.NONE)))
                        .toList();
        PostLike first = likes.get(0);
        PostLike last = likes.get(likes.size() - 1);
        String startCursor = encodeCursor(first.getCreatedAt(), first.getId().getUserId());
        String endCursor = encodeCursor(last.getCreatedAt(), last.getId().getUserId());
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<LikedPostResponse> listLikedPosts(
            UUID userId, String cursor, int size) {
        int pageSize = normalizeLimit(size);
        Cursor decoded = decodeLikedCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<PostLike> likes =
                decoded == null
                        ? postLikeRepository.findFirstLikes(userId, page)
                        : postLikeRepository.findLikesBefore(
                                userId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        boolean hasNextPage = likes.size() > pageSize;
        if (hasNextPage) {
            likes = likes.subList(0, pageSize);
        }
        if (likes.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        List<UUID> postIds = likes.stream().map(l -> l.getId().getPostId()).toList();
        // findAllById drops soft-deleted posts via the entity's @SQLRestriction filter.
        Map<UUID, Post> posts =
                postRepository.findAllById(postIds).stream()
                        .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                        .filter(p -> postVisibilityService.isVisibleTo(userId, p))
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        // Post-fetch filtering can shrink a page below the requested size; cursors stay correct
        // because they encode post_likes.created_at, not row counts.
        List<PostLike> visibleLikes =
                likes.stream().filter(l -> posts.containsKey(l.getId().getPostId())).toList();
        List<Post> orderedPosts =
                visibleLikes.stream().map(l -> posts.get(l.getId().getPostId())).toList();
        List<PostResponse> responses = postResponseAssembler.assemble(userId, orderedPosts);
        List<LikedPostResponse> content = new ArrayList<>(visibleLikes.size());
        for (int i = 0; i < visibleLikes.size(); i++) {
            content.add(
                    new LikedPostResponse(responses.get(i), visibleLikes.get(i).getCreatedAt()));
        }
        PostLike firstLike = likes.get(0);
        PostLike lastLike = likes.get(likes.size() - 1);
        String startCursor =
                encodeLikedCursor(firstLike.getCreatedAt(), firstLike.getId().getPostId());
        String endCursor = encodeLikedCursor(lastLike.getCreatedAt(), lastLike.getId().getPostId());
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    // Enqueued inside the caller's transaction: OutboxService.enqueue is PROPAGATION.MANDATORY,
    // so a like that rolls back cannot leave an event behind announcing it. The payload carries
    // identifiers only - the like count is re-read by the live consumer at push time, because the
    // outbox publisher runs after this transaction commits and any count captured here would
    // already be stale by then.
    private void enqueueLiveEvent(String eventType, Post post, UUID actorId) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        // Consumed by the live tier to resolve the post owner's block counterparties; the actor is
        // deliberately absent from the broadcast payload, since every subscriber of the post would
        // otherwise learn who liked it.
        data.put("postOwnerId", post.getUserId().toString());
        outboxService.enqueue(eventType, eventType, AGGREGATE_TYPE, post.getId(), actorId, data);
    }

    private Post fetchVisiblePublishedPost(UUID viewerId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        // Unpublished posts surface as not-found to avoid leaking their existence.
        if (post.getStatus() != PostStatus.PUBLISHED && !viewerId.equals(post.getUserId())) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        if (!postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }

    private String encodeCursor(OffsetDateTime time, UUID tiebreaker) {
        if (time == null || tiebreaker == null) {
            return null;
        }
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(time), tiebreaker), CursorScope.POST_LIKERS);
    }

    private Cursor decodeCursor(String cursor) {
        return CursorCodec.decode(cursor, CursorScope.POST_LIKERS);
    }

    private String encodeLikedCursor(OffsetDateTime time, UUID tiebreaker) {
        if (time == null || tiebreaker == null) {
            return null;
        }
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(time), tiebreaker), CursorScope.POST_LIKED_POSTS);
    }

    private Cursor decodeLikedCursor(String cursor) {
        return CursorCodec.decode(cursor, CursorScope.POST_LIKED_POSTS);
    }
}
