package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import com.app.common.response.UserSummaryResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostEditHistoryResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostEditHistory;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.mapper.PostMapper;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.repository.PostEditHistoryRepository;
import com.app.modules.post.repository.PostModerationProjection;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostMentionService;
import com.app.modules.post.service.PostMentionService.MentionChange;
import com.app.modules.post.service.PostModerationResult;
import com.app.modules.post.service.PostPublicationService;
import com.app.modules.post.service.PostPublicationService.Source;
import com.app.modules.post.service.PostService;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.post.validation.PostTypeFilter;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PostServiceImpl implements PostService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final UUID NO_AUTHOR = new UUID(0L, 0L);

    private final PostRepository postRepository;
    private final PostEditHistoryRepository postEditHistoryRepository;
    private final PostUserRepository postUserRepository;
    private final PostCaptionHashtags captionHashtags;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;
    private final PostMapper postMapper;
    private final SocialService socialService;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;
    private final PostContentWriter postContentWriter;
    private final PostPublicationService postPublicationService;
    private final PostSchedulePolicy postSchedulePolicy;
    private final PostMentionService postMentionService;
    private final PostIndexEventPublisher postIndexEventPublisher;
    private final PlaceRepository placeRepository;

    public PostServiceImpl(
            PostRepository postRepository,
            PostEditHistoryRepository postEditHistoryRepository,
            PostUserRepository postUserRepository,
            PostCaptionHashtags captionHashtags,
            PostVisibilityService postVisibilityService,
            PostResponseAssembler postResponseAssembler,
            PostMapper postMapper,
            SocialService socialService,
            OutboxService outboxService,
            UserSummaryService userSummaryService,
            PostContentWriter postContentWriter,
            PostPublicationService postPublicationService,
            PostSchedulePolicy postSchedulePolicy,
            PostMentionService postMentionService,
            PostIndexEventPublisher postIndexEventPublisher,
            PlaceRepository placeRepository) {
        this.postRepository = postRepository;
        this.postEditHistoryRepository = postEditHistoryRepository;
        this.postUserRepository = postUserRepository;
        this.captionHashtags = captionHashtags;
        this.postVisibilityService = postVisibilityService;
        this.postResponseAssembler = postResponseAssembler;
        this.postMapper = postMapper;
        this.socialService = socialService;
        this.outboxService = outboxService;
        this.userSummaryService = userSummaryService;
        this.postContentWriter = postContentWriter;
        this.postPublicationService = postPublicationService;
        this.postSchedulePolicy = postSchedulePolicy;
        this.postMentionService = postMentionService;
        this.postIndexEventPublisher = postIndexEventPublisher;
        this.placeRepository = placeRepository;
    }

    @Override
    @Transactional
    public PostResponse createPost(UUID authorId, CreatePostRequest request) {
        PostStatus target = request.status() == null ? PostStatus.PUBLISHED : request.status();
        if (target != PostStatus.DRAFT
                && target != PostStatus.SCHEDULED
                && target != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid initial post status");
        }
        OffsetDateTime scheduledAt = null;
        if (target == PostStatus.SCHEDULED) {
            scheduledAt = postSchedulePolicy.validate(request.scheduledAt());
        } else if (request.scheduledAt() != null) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "scheduledAt is only valid for a scheduled post");
        }
        if (target != PostStatus.PUBLISHED) {
            postSchedulePolicy.requireRoomForUnpublished(authorId);
        }
        // A post to be published is stored as a draft first and handed to the publication routine,
        // so create-as-published and publish-a-draft cannot drift apart.
        Post post =
                postContentWriter.create(
                        authorId,
                        PostContentWriter.Content.from(request),
                        target == PostStatus.SCHEDULED ? PostStatus.SCHEDULED : PostStatus.DRAFT,
                        scheduledAt);
        switch (target) {
            case PUBLISHED -> postPublicationService.publish(post, Source.OWNER);
            case SCHEDULED -> enqueueScheduled(post);
            default -> enqueueLifecycle(PostEventTypes.POST_DRAFT_SAVED_V1, post);
        }
        log.info(
                "Post created: postId={}, type={}, status={}",
                post.getId(),
                post.getPostType(),
                post.getStatus());
        return postResponseAssembler.assemble(authorId, post);
    }

    @Override
    @Transactional(readOnly = true)
    public PostResponse getPostById(UUID viewerId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        boolean isOwner = viewerId.equals(post.getUserId());
        // Non-owners must not learn that an unpublished post exists.
        if (!isOwner && post.getStatus() != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        if (!postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        return postResponseAssembler.assemble(viewerId, post);
    }

    @Override
    @Transactional
    public PostResponse updateCaption(
            UUID requesterId, UUID postId, UpdatePostCaptionRequest request) {
        Post post = requireOwnedForUpdate(requesterId, postId);
        captionHashtags.rejectBanned(request.caption());
        // The audit row records the pre-edit caption before any mutation.
        postEditHistoryRepository.save(
                PostEditHistory.builder()
                        .postId(post.getId())
                        .editorId(requesterId)
                        .previousCaption(post.getCaption())
                        .build());
        post.setCaption(request.caption());
        MentionChange mentions = postMentionService.replaceCaptionMentions(post);
        if (post.getStatus() == PostStatus.PUBLISHED) {
            // Keep post_hashtags consistent with the published caption.
            captionHashtags.refresh(post.getId(), post.getCaption());
            postIndexEventPublisher.enqueueUpsert(post);
            // Recorded for the behavioural signals, never notified: the people were told, or
            // deliberately not told, when the post was published.
            postMentionService.announce(post, mentions.added(), List.of());
        }
        return postResponseAssembler.assemble(requesterId, post);
    }

    @Override
    @Transactional
    public PostResponse transitionStatus(
            UUID requesterId, UUID postId, PostStatusTransitionRequest request) {
        Post post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        PostStatus target = request.targetStatus();
        boolean isOwner = requesterId.equals(post.getUserId());
        if (target == PostStatus.REMOVED) {
            if (!isOwner && !isCurrentUserAdmin()) {
                throw new AppException(ApiErrorCode.POST_FORBIDDEN);
            }
            requireScheduledAtAbsent(request);
            softDelete(post);
            return postResponseAssembler.assemble(requesterId, post);
        }
        if (!isOwner) {
            // A non-owner must not learn that an unpublished post exists.
            throw new AppException(
                    post.getStatus() == PostStatus.PUBLISHED
                            ? ApiErrorCode.POST_FORBIDDEN
                            : ApiErrorCode.POST_NOT_FOUND);
        }
        PostStatus current = post.getStatus();
        if (!isAllowedTransition(current, target)) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid status transition");
        }
        if (target == PostStatus.SCHEDULED) {
            post.setScheduledAt(postSchedulePolicy.validate(request.scheduledAt()));
            post.setStatus(PostStatus.SCHEDULED);
            postRepository.saveAndFlush(post);
            enqueueScheduled(post);
        } else {
            requireScheduledAtAbsent(request);
            switch (target) {
                case PUBLISHED -> {
                    boolean unarchive = current == PostStatus.ARCHIVED;
                    postPublicationService.publish(post, Source.OWNER);
                    if (unarchive) {
                        enqueueLifecycle(PostEventTypes.POST_UNARCHIVED_V1, post);
                    }
                }
                case ARCHIVED -> {
                    post.setStatus(PostStatus.ARCHIVED);
                    postRepository.saveAndFlush(post);
                    // Unpublishing removes hashtag associations (hashtag module data rules).
                    captionHashtags.detach(post.getId());
                    postIndexEventPublisher.enqueueDelete(post);
                    enqueueLifecycle(PostEventTypes.POST_ARCHIVED_V1, post);
                }
                default -> {
                    // SCHEDULED to DRAFT: the owner took the post off the schedule.
                    post.setStatus(PostStatus.DRAFT);
                    post.setScheduledAt(null);
                    postRepository.saveAndFlush(post);
                    enqueueLifecycle(PostEventTypes.POST_DRAFT_SAVED_V1, post);
                }
            }
        }
        log.info(
                "Post status transitioned: postId={}, from={}, to={}",
                post.getId(),
                current,
                target);
        return postResponseAssembler.assemble(requesterId, post);
    }

    @Override
    @Transactional
    public void deletePost(UUID requesterId, UUID postId) {
        Post post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (!requesterId.equals(post.getUserId()) && !isCurrentUserAdmin()) {
            throw new AppException(
                    post.getStatus() == PostStatus.PUBLISHED
                            ? ApiErrorCode.POST_FORBIDDEN
                            : ApiErrorCode.POST_NOT_FOUND);
        }
        softDelete(post);
    }

    @Override
    @Transactional
    public PostModerationResult applyModerationRemoval(UUID postId) {
        PostModerationProjection post = requireModerationView(postId);
        captionHashtags.detach(postId);
        postRepository.applyModerationRemoval(postId, OffsetDateTime.now());
        postIndexEventPublisher.enqueueDelete(postId, post.getUserId());
        postIndexEventPublisher.syncPlace(post.getPlaceId());
        log.info("Post removed by moderation: postId={}, from={}", postId, post.getStatus());
        return new PostModerationResult(post.getUserId(), PostStatus.REMOVED, List.of());
    }

    @Override
    @Transactional
    public PostModerationResult applyModerationRestore(UUID postId) {
        PostModerationProjection post = requireModerationView(postId);
        // Null for a post removed before the prior status was recorded. Published is the right
        // fallback rather than a guess: it is what restore did for every post back then, so a row
        // from that era lands exactly where it would have.
        PostStatus restored = restoredStatusOrPublished(post.getStatusBeforeModeration());
        // A scheduled post whose time passed while it was removed must not publish the instant it
        // is restored, with no one having chosen that moment; it returns as a draft.
        if (restored == PostStatus.SCHEDULED
                && (post.getScheduledAt() == null
                        || !post.getScheduledAt().isAfter(java.time.Instant.now()))) {
            restored = PostStatus.DRAFT;
        }
        // The place may have been banned or deleted while the post was removed. The restore drops
        // it, like it drops a banned hashtag, rather than refusing: a moderator correcting its own
        // removal must not be blocked by an administrator decision it cannot reverse.
        UUID keptPlaceId = post.getPlaceId();
        String strippedPlaceName = null;
        if (keptPlaceId != null) {
            Place place = placeRepository.findById(keptPlaceId).orElse(null);
            if (place == null || place.getStatus() != PlaceStatus.ACTIVE) {
                strippedPlaceName = place == null ? null : place.getName();
                keptPlaceId = null;
            }
        }
        postRepository.applyModerationRestore(postId, restored.toJson(), keptPlaceId);
        // Only a published post belongs in post_hashtags and in the search index; the owner path
        // keeps a draft and an archived post out of both. The hashtag-eligibility check belongs on
        // this re-derivation rather than on the removal arm, because removal detaches every
        // association regardless of which tag it is.
        //
        // This is the one write path that strips a banned tag instead of refusing. The stripped
        // names travel back to the caller so the audit row records exactly what was dropped and the
        // moderator is told rather than finding out later.
        List<String> remainingBannedHashtags = List.of();
        if (restored == PostStatus.PUBLISHED) {
            List<String> tags = captionHashtags.extract(post.getCaption());
            if (!tags.isEmpty()) {
                remainingBannedHashtags = captionHashtags.upsertSkippingBanned(postId, tags);
            }
            postIndexEventPublisher.enqueueUpsert(
                    postId,
                    post.getUserId(),
                    post.getCreatedAt().atOffset(ZoneOffset.UTC),
                    post.getPublishedAt() == null
                            ? null
                            : post.getPublishedAt().atOffset(ZoneOffset.UTC));
            postIndexEventPublisher.syncPlace(post.getPlaceId());
        }
        log.info(
                "Post restored by moderation: postId={}, to={}, remainingBannedHashtags={},"
                        + " strippedPlace={}",
                postId,
                restored,
                remainingBannedHashtags,
                strippedPlaceName != null);
        return new PostModerationResult(
                post.getUserId(), restored, remainingBannedHashtags, strippedPlaceName);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PostResponse> listUserPosts(
            UUID viewerId, UUID targetUserId, PostTypeFilter typeFilter, String cursor, int size) {
        requireProfileReadable(viewerId, targetUserId);
        int pageSize = normalizeLimit(size);
        // The filter is bound into the cursor scope, so a cursor cannot be carried across filters.
        // The ordering is identical either way, which is exactly why a replay would otherwise
        // succeed while silently omitting the rows the other filter excludes before this position.
        String scope = typeFilter.cursorScope();
        Cursor decoded = decodeCursor(cursor, scope);
        // One row more than the page, to learn whether another page exists.
        int fetchSize = pageSize + 1;
        List<Post> posts;
        if (typeFilter.isEmpty()) {
            posts =
                    decoded == null
                            ? postRepository.findFirstUserPosts(targetUserId, fetchSize)
                            : postRepository.findUserPostsBefore(
                                    targetUserId,
                                    TimeCursors.fromMicros(decoded.sortValueMicros()),
                                    decoded.id(),
                                    fetchSize);
        } else {
            String types = typeFilter.asDelimitedTypes();
            posts =
                    decoded == null
                            ? postRepository.findFirstUserPostsByType(
                                    targetUserId, types, fetchSize)
                            : postRepository.findUserPostsByTypeBefore(
                                    targetUserId,
                                    types,
                                    TimeCursors.fromMicros(decoded.sortValueMicros()),
                                    decoded.id(),
                                    fetchSize);
        }
        boolean hasNextPage = posts.size() > pageSize;
        if (hasNextPage) {
            posts = posts.subList(0, pageSize);
        }
        if (posts.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        Post first = posts.get(0);
        Post last = posts.get(posts.size() - 1);
        String startCursor = encodeCursor(first.getPublishedAt(), first.getId(), scope);
        String endCursor = encodeCursor(last.getPublishedAt(), last.getId(), scope);
        // A co-authored post on someone else's profile is governed by its owner's privacy and by
        // blocks with every author, which the profile gate above did not look at. The cursors come
        // from the unfiltered page, so a page the filter shortens still walks forward correctly.
        List<Post> visible = keepVisible(viewerId, targetUserId, posts);
        List<PostResponse> content =
                visible.isEmpty() ? List.of() : postResponseAssembler.assemble(viewerId, visible);
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PostResponse> listTaggedPosts(
            UUID viewerId, UUID targetUserId, String cursor, int size) {
        requireProfileReadable(viewerId, targetUserId);
        int pageSize = normalizeLimit(size);
        Cursor decoded = decodeCursor(cursor, CursorScope.TAGGED_POSTS);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Post> posts =
                decoded == null
                        ? postRepository.findFirstTaggedPosts(targetUserId, page)
                        : postRepository.findTaggedPostsBefore(
                                targetUserId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        boolean hasNextPage = posts.size() > pageSize;
        if (hasNextPage) {
            posts = posts.subList(0, pageSize);
        }
        if (posts.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        Post first = posts.get(0);
        Post last = posts.get(posts.size() - 1);
        String startCursor =
                encodeCursor(first.getPublishedAt(), first.getId(), CursorScope.TAGGED_POSTS);
        String endCursor =
                encodeCursor(last.getPublishedAt(), last.getId(), CursorScope.TAGGED_POSTS);
        List<Post> visible = keepVisible(viewerId, targetUserId, posts);
        List<PostResponse> content =
                visible.isEmpty() ? List.of() : postResponseAssembler.assemble(viewerId, visible);
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<FeedPostResponse> getFeed(
            UUID viewerId, String cursor, int size, FeedSurface surface) {
        // Decoded before the empty-follow-set short-circuit below so a malformed cursor is
        // rejected the same way regardless of how many accounts the viewer follows, instead of
        // silently returning an empty page for a viewer who follows nobody.
        Cursor decoded = decodeCursor(cursor, CursorScope.POST_FEED);
        List<UUID> followedIds = socialService.getAcceptedFollowingExcludingBlocks(viewerId);
        int pageSize = normalizeLimit(size);
        if (followedIds.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        // Authors are removed before the query rather than rows afterwards, so a page is never
        // served short and the keyset cursor never sees a row the viewer will not. A post is
        // hidden when any of its authors is excluded, so the exclusion list also carries the
        // viewer's blocks and every author the viewer muted, not only the followed ones.
        Set<UUID> suppressed = socialService.findAllFeedSuppressedAuthors(viewerId, surface);
        List<UUID> authorIds =
                suppressed.isEmpty()
                        ? followedIds
                        : followedIds.stream().filter(id -> !suppressed.contains(id)).toList();
        if (authorIds.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        Set<UUID> excluded = new HashSet<>(suppressed);
        excluded.addAll(socialService.findBlockedEitherDirection(viewerId));
        List<UUID> excludedAuthorIds =
                excluded.isEmpty() ? List.of(NO_AUTHOR) : List.copyOf(excluded);
        int fetchSize = pageSize + 1;
        List<Post> posts =
                decoded == null
                        ? postRepository.findFirstFeedPosts(
                                authorIds, excludedAuthorIds, viewerId, fetchSize)
                        : postRepository.findFeedPostsBefore(
                                authorIds,
                                excludedAuthorIds,
                                viewerId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                fetchSize);
        boolean hasNextPage = posts.size() > pageSize;
        if (hasNextPage) {
            posts = posts.subList(0, pageSize);
        }
        if (posts.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        List<FeedPostResponse> content = postResponseAssembler.assembleFeed(viewerId, posts);
        Post first = posts.get(0);
        Post last = posts.get(posts.size() - 1);
        String startCursor =
                encodeCursor(first.getPublishedAt(), first.getId(), CursorScope.POST_FEED);
        String endCursor = encodeCursor(last.getPublishedAt(), last.getId(), CursorScope.POST_FEED);
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PostEditHistoryResponse> listEditHistory(
            UUID requesterId, UUID postId, String cursor, int size) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (!requesterId.equals(post.getUserId())) {
            throw new AppException(
                    post.getStatus() == PostStatus.PUBLISHED
                            ? ApiErrorCode.POST_FORBIDDEN
                            : ApiErrorCode.POST_NOT_FOUND);
        }
        int pageSize = normalizeLimit(size);
        Cursor decoded = decodeCursor(cursor, CursorScope.POST_EDIT_HISTORY);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<PostEditHistory> rows =
                decoded == null
                        ? postEditHistoryRepository.findFirstByPost(postId, page)
                        : postEditHistoryRepository.findByPostBefore(
                                postId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        boolean hasNextPage = rows.size() > pageSize;
        if (hasNextPage) {
            rows = rows.subList(0, pageSize);
        }
        if (rows.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        Map<UUID, UserSummaryResponse> editors =
                userSummaryService.loadSummaries(
                        rows.stream().map(PostEditHistory::getEditorId).toList());
        List<PostEditHistoryResponse> content =
                rows.stream()
                        .map(h -> postMapper.toEditHistoryResponse(h, editors.get(h.getEditorId())))
                        .toList();
        PostEditHistory first = rows.get(0);
        PostEditHistory last = rows.get(rows.size() - 1);
        String startCursor =
                encodeCursor(first.getEditedAt(), first.getId(), CursorScope.POST_EDIT_HISTORY);
        String endCursor =
                encodeCursor(last.getEditedAt(), last.getId(), CursorScope.POST_EDIT_HISTORY);
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    // The valid owner transitions. A post that was ever published never goes back to draft: the
    // only way to a draft from a scheduled post is the schedule coming off, and a scheduled post
    // cannot have been published.
    private static boolean isAllowedTransition(PostStatus current, PostStatus target) {
        return switch (current) {
            case DRAFT -> target == PostStatus.PUBLISHED || target == PostStatus.SCHEDULED;
            case SCHEDULED ->
                    target == PostStatus.DRAFT
                            || target == PostStatus.SCHEDULED
                            || target == PostStatus.PUBLISHED;
            case PUBLISHED -> target == PostStatus.ARCHIVED;
            case ARCHIVED -> target == PostStatus.PUBLISHED;
            case REMOVED -> false;
        };
    }

    private static void requireScheduledAtAbsent(PostStatusTransitionRequest request) {
        if (request.scheduledAt() != null) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "scheduledAt is only valid when scheduling a post");
        }
    }

    private static PostStatus restoredStatusOrPublished(String statusBeforeModeration) {
        PostStatus restored = PostStatus.fromJson(statusBeforeModeration);
        return restored == null ? PostStatus.PUBLISHED : restored;
    }

    // Soft delete keeps the row (GLOBAL_RULES soft delete policy): status flip + deleted_at; the
    // trg_post_count trigger adjusts users.post_count on the same UPDATE statement.
    private void softDelete(Post post) {
        boolean wasPublished = post.getStatus() == PostStatus.PUBLISHED;
        post.setStatus(PostStatus.REMOVED);
        post.setDeletedAt(OffsetDateTime.now());
        captionHashtags.detach(post.getId());
        if (wasPublished) {
            postIndexEventPublisher.enqueueDelete(post);
        }
    }

    private Post requireOwnedForUpdate(UUID requesterId, UUID postId) {
        Post post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (!requesterId.equals(post.getUserId())) {
            throw new AppException(
                    post.getStatus() == PostStatus.PUBLISHED
                            ? ApiErrorCode.POST_FORBIDDEN
                            : ApiErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private User requireProfileReadable(UUID viewerId, UUID targetUserId) {
        User target =
                postUserRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.NOT_FOUND, "User not found"));
        // Stealth block model: matches assemblePublicProfile's reference behaviour - a block in
        // either direction must be indistinguishable from targetUserId not existing.
        if (socialService.findBlockedEitherDirection(viewerId).contains(targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
        }
        boolean isOwner = viewerId.equals(targetUserId);
        if (target.isPrivate()
                && !isOwner
                && !socialService.hasAcceptedFollow(viewerId, targetUserId)) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        return target;
    }

    // Posts the viewer may not see by their own visibility: the owner's privacy and blocks with
    // any author. Only evaluated for posts the profile owner does not own, since the profile gate
    // already covered the rest.
    private List<Post> keepVisible(UUID viewerId, UUID profileUserId, List<Post> posts) {
        Map<UUID, UUID> ownerByPostId = new HashMap<>();
        for (Post post : posts) {
            if (!post.getUserId().equals(profileUserId)) {
                ownerByPostId.put(post.getId(), post.getUserId());
            }
        }
        if (ownerByPostId.isEmpty()) {
            return posts;
        }
        Set<UUID> visible = postVisibilityService.filterVisiblePostIds(viewerId, ownerByPostId);
        List<Post> kept = new ArrayList<>(posts.size());
        for (Post post : posts) {
            if (!ownerByPostId.containsKey(post.getId()) || visible.contains(post.getId())) {
                kept.add(post);
            }
        }
        return kept;
    }

    private PostModerationProjection requireModerationView(UUID postId) {
        return postRepository
                .findModerationViewIncludingDeleted(postId)
                .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
    }

    private void enqueueScheduled(Post post) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("scheduledAt", post.getScheduledAt().toString());
        data.put(
                "leadMinutes",
                Math.max(
                        0,
                        java.time.Duration.between(OffsetDateTime.now(), post.getScheduledAt())
                                .toMinutes()));
        outboxService.enqueue(
                PostEventTypes.POST_SCHEDULED_V1,
                PostEventTypes.POST_SCHEDULED_V1,
                "post",
                post.getId(),
                post.getUserId(),
                data);
    }

    private void enqueueLifecycle(String eventType, Post post) {
        outboxService.enqueue(
                eventType,
                eventType,
                "post",
                post.getId(),
                post.getUserId(),
                Map.of("postId", post.getId().toString()));
    }

    // SecurityUtils.getCurrentUser throws when no principal is bound (system/test contexts);
    // absence of a principal must read as "not an admin", not as an authorization error.
    private boolean isCurrentUserAdmin() {
        try {
            return SecurityUtils.isAdmin();
        } catch (AppException e) {
            return false;
        }
    }

    private int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }

    private String encodeCursor(OffsetDateTime time, UUID id, String scope) {
        if (time == null || id == null) {
            return null;
        }
        return CursorCodec.encode(new Cursor(TimeCursors.toMicros(time), id), scope);
    }

    private Cursor decodeCursor(String cursor, String scope) {
        return CursorCodec.decode(cursor, scope);
    }
}
