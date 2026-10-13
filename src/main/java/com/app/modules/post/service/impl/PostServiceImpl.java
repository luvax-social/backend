package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.media.enums.MediaType;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostEditHistoryResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostEditHistory;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.mapper.PostMapper;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostEditHistoryRepository;
import com.app.modules.post.repository.PostMediaAssetRepository;
import com.app.modules.post.repository.PostModerationProjection;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostModerationResult;
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

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#([\\p{L}\\p{N}_]+)");
    private static final String MAX_POST_MEDIA_ITEMS = "max_post_media_items";
    private static final String MAX_HASHTAGS_PER_POST = "max_hashtags_per_post";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PostRepository postRepository;
    private final PostEditHistoryRepository postEditHistoryRepository;
    private final PostUserRepository postUserRepository;
    private final PostMediaAssetRepository postMediaAssetRepository;
    private final HashtagService hashtagService;
    private final SystemSettingService systemSettingService;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;
    private final PostMapper postMapper;
    private final SocialService socialService;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;

    public PostServiceImpl(
            PostRepository postRepository,
            PostEditHistoryRepository postEditHistoryRepository,
            PostUserRepository postUserRepository,
            PostMediaAssetRepository postMediaAssetRepository,
            HashtagService hashtagService,
            SystemSettingService systemSettingService,
            PostVisibilityService postVisibilityService,
            PostResponseAssembler postResponseAssembler,
            PostMapper postMapper,
            SocialService socialService,
            OutboxService outboxService,
            UserSummaryService userSummaryService) {
        this.postRepository = postRepository;
        this.postEditHistoryRepository = postEditHistoryRepository;
        this.postUserRepository = postUserRepository;
        this.postMediaAssetRepository = postMediaAssetRepository;
        this.hashtagService = hashtagService;
        this.systemSettingService = systemSettingService;
        this.postVisibilityService = postVisibilityService;
        this.postResponseAssembler = postResponseAssembler;
        this.postMapper = postMapper;
        this.socialService = socialService;
        this.outboxService = outboxService;
        this.userSummaryService = userSummaryService;
    }

    @Override
    @Transactional
    public PostResponse createPost(UUID authorId, CreatePostRequest request) {
        PostStatus initialStatus =
                request.status() == null ? PostStatus.PUBLISHED : request.status();
        if (initialStatus != PostStatus.DRAFT && initialStatus != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid initial post status");
        }
        if (request.postType() == PostType.TEXT) {
            if (request.caption() == null || request.caption().isBlank()) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Text posts require a non-blank caption");
            }
            if (request.mediaIds() != null && !request.mediaIds().isEmpty()) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Text posts must not include media");
            }
            rejectBannedHashtags(request.caption());
            Post post =
                    Post.builder()
                            .userId(authorId)
                            .caption(request.caption())
                            .postType(PostType.TEXT)
                            .status(initialStatus)
                            .locationName(request.locationName())
                            .latitude(request.latitude())
                            .longitude(request.longitude())
                            .build();
            // Flush so the DB-assigned id and the database-generated createdAt are populated
            // before the index-upsert event payload reads them.
            postRepository.saveAndFlush(post);
            if (initialStatus == PostStatus.PUBLISHED) {
                upsertCaptionHashtags(post.getId(), post.getCaption());
                enqueuePostIndexUpsert(post);
            }
            log.info("Post created: postId={}, type={}", post.getId(), post.getPostType());
            return postResponseAssembler.assemble(authorId, post);
        }
        rejectBannedHashtags(request.caption());
        List<UUID> mediaIds = request.mediaIds();
        if (mediaIds == null || mediaIds.isEmpty()) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Media posts require at least one media item");
        }
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Duplicate media ids");
        }
        Map<UUID, MediaAsset> assets =
                postMediaAssetRepository.findAllById(mediaIds).stream()
                        .collect(Collectors.toMap(MediaAsset::getId, a -> a));
        if (assets.size() != mediaIds.size()) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Media asset not found");
        }
        for (MediaAsset asset : assets.values()) {
            if (!asset.getUserId().equals(authorId)) {
                throw new AppException(
                        ApiErrorCode.POST_FORBIDDEN, "Media asset not owned by author");
            }
        }
        validateMediaCardinality(request.postType(), mediaIds, assets);

        Post post =
                Post.builder()
                        .userId(authorId)
                        .caption(request.caption())
                        .postType(request.postType())
                        .status(initialStatus)
                        .locationName(request.locationName())
                        .latitude(request.latitude())
                        .longitude(request.longitude())
                        .build();
        for (int i = 0; i < mediaIds.size(); i++) {
            post.getMedia()
                    .add(
                            PostMedia.builder()
                                    .post(post)
                                    .mediaAssetId(mediaIds.get(i))
                                    .position((short) i)
                                    .build());
        }
        // Flush so the DB-assigned id and @CreationTimestamp createdAt are populated before the
        // index-upsert event payload reads them.
        postRepository.saveAndFlush(post);
        if (initialStatus == PostStatus.PUBLISHED) {
            upsertCaptionHashtags(post.getId(), post.getCaption());
            enqueuePostIndexUpsert(post);
        }
        log.info("Post created: postId={}, type={}", post.getId(), post.getPostType());
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
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (!requesterId.equals(post.getUserId())) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        rejectBannedHashtags(request.caption());
        // The audit row records the pre-edit caption before any mutation.
        postEditHistoryRepository.save(
                PostEditHistory.builder()
                        .postId(post.getId())
                        .editorId(requesterId)
                        .previousCaption(post.getCaption())
                        .build());
        post.setCaption(request.caption());
        if (post.getStatus() == PostStatus.PUBLISHED) {
            // Keep post_hashtags consistent with the published caption.
            hashtagService.removeHashtagsForPost(post.getId());
            upsertCaptionHashtags(post.getId(), post.getCaption());
            enqueuePostIndexUpsert(post);
        }
        return postResponseAssembler.assemble(requesterId, post);
    }

    @Override
    @Transactional
    public PostResponse transitionStatus(
            UUID requesterId, UUID postId, PostStatusTransitionRequest request) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        PostStatus target = request.targetStatus();
        boolean isOwner = requesterId.equals(post.getUserId());
        if (target == PostStatus.REMOVED) {
            if (!isOwner && !isCurrentUserAdmin()) {
                throw new AppException(ApiErrorCode.POST_FORBIDDEN);
            }
            softDelete(post);
            return postResponseAssembler.assemble(requesterId, post);
        }
        if (!isOwner) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        PostStatus current = post.getStatus();
        boolean allowed =
                (current == PostStatus.DRAFT && target == PostStatus.PUBLISHED)
                        || (current == PostStatus.PUBLISHED && target == PostStatus.ARCHIVED)
                        || (current == PostStatus.ARCHIVED && target == PostStatus.PUBLISHED);
        if (!allowed) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid status transition");
        }
        if (target == PostStatus.PUBLISHED) {
            // The path a check on the create routes alone would miss: a post drafted or archived
            // before the ban carries the tag, and publishing is when that tag would reach
            // post_hashtags and the search index for the first time since.
            rejectBannedHashtags(post.getCaption());
        }
        post.setStatus(target);
        if (target == PostStatus.PUBLISHED) {
            upsertCaptionHashtags(post.getId(), post.getCaption());
            enqueuePostIndexUpsert(post);
        } else if (target == PostStatus.ARCHIVED) {
            // Unpublishing removes hashtag associations (hashtag module data rules).
            hashtagService.removeHashtagsForPost(post.getId());
            enqueuePostIndexDelete(post);
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
        PostModerationProjection post = requireModerationView(postId);
        if (post.getDeletedAt() != null) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        if (!requesterId.equals(post.getUserId()) && !isCurrentUserAdmin()) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        if (postRepository.applyOwnerDeletion(postId, OffsetDateTime.now()) != 1) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        hashtagService.removeHashtagsForPost(postId);
        enqueuePostIndexDelete(postId, post.getUserId());
    }

    @Override
    @Transactional
    public PostModerationResult applyModerationRemoval(UUID postId) {
        PostModerationProjection post = requireModerationView(postId);
        if (postRepository.applyModerationRemoval(postId) != 1) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        hashtagService.removeHashtagsForPost(postId);
        enqueuePostIndexDelete(postId, post.getUserId());
        log.info("Post removed by moderation: postId={}, from={}", postId, post.getStatus());
        return new PostModerationResult(post.getUserId(), PostStatus.REMOVED, List.of());
    }

    @Override
    @Transactional
    public PostModerationResult applyModerationRestore(UUID postId) {
        PostModerationProjection post = requireModerationView(postId);
        // A legacy deleted_at value cannot prove who deleted the post. Fail closed
        // rather than treating an owner tombstone as a moderation-only removal.
        PostStatus restored = PostStatus.fromJson(post.getStatusBeforeModeration());
        if (post.getDeletedAt() != null || restored == null || restored == PostStatus.REMOVED
                || postRepository.applyModerationRestore(postId, restored.toJson()) != 1) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        // Only a published post belongs in post_hashtags and in the search index; the owner path
        // keeps a draft and an archived post out of both. The hashtag-eligibility check belongs on
        // this re-derivation rather than on the removal arm, because removal detaches every
        // association regardless of which tag it is.
        //
        // This is the one write path that strips a banned tag instead of refusing. A moderator
        // restoring a post it removed by mistake is correcting its own error and must not be
        // blocked by an unrelated administrator decision it has no power to reverse; refusing here
        // would leave the post removed with no in-role way to bring it back. The stripped names
        // travel back to the caller so the audit row records exactly what was dropped and the
        // moderator is told rather than finding out later.
        List<String> remainingBannedHashtags = List.of();
        if (restored == PostStatus.PUBLISHED) {
            List<String> tags = extractHashtags(post.getCaption());
            if (!tags.isEmpty()) {
                remainingBannedHashtags =
                        hashtagService.upsertHashtagsForPostSkippingBanned(postId, tags);
            }
            enqueuePostIndexUpsert(
                    postId, post.getUserId(), post.getCreatedAt().atOffset(ZoneOffset.UTC));
        }
        log.info(
                "Post restored by moderation: postId={}, to={}, remainingBannedHashtags={}",
                postId,
                restored,
                remainingBannedHashtags);
        return new PostModerationResult(post.getUserId(), restored, remainingBannedHashtags);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PostResponse> listUserPosts(
            UUID viewerId, UUID targetUserId, PostTypeFilter typeFilter, String cursor, int size) {
        User target =
                postUserRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.NOT_FOUND, "User not found"));
        // Stealth block model: matches assemblePublicProfile's reference behaviour - a block in
        // either direction must be indistinguishable from targetUserId not existing.
        if (socialService.isBlockedBetween(viewerId, targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
        }
        boolean isOwner = viewerId.equals(targetUserId);
        if (target.isPrivate()
                && !isOwner
                && !socialService.hasAcceptedFollow(viewerId, targetUserId)) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        int pageSize = normalizeLimit(size);
        // The filter is bound into the cursor scope, so a cursor cannot be carried across filters.
        // The ordering is identical either way, which is exactly why a replay would otherwise
        // succeed while silently omitting the rows the other filter excludes before this position.
        String scope = typeFilter.cursorScope();
        Cursor decoded = decodeCursor(cursor, scope);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Post> posts;
        if (typeFilter.isEmpty()) {
            posts =
                    decoded == null
                            ? postRepository.findFirstUserPosts(targetUserId, page)
                            : postRepository.findUserPostsBefore(
                                    targetUserId,
                                    TimeCursors.fromMicros(decoded.sortValueMicros()),
                                    decoded.id(),
                                    page);
        } else {
            String types = typeFilter.asDelimitedTypes();
            posts =
                    decoded == null
                            ? postRepository.findFirstUserPostsByType(targetUserId, types, page)
                            : postRepository.findUserPostsByTypeBefore(
                                    targetUserId,
                                    types,
                                    TimeCursors.fromMicros(decoded.sortValueMicros()),
                                    decoded.id(),
                                    page);
        }
        boolean hasNextPage = posts.size() > pageSize;
        if (hasNextPage) {
            posts = posts.subList(0, pageSize);
        }
        if (posts.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        List<PostResponse> content = postResponseAssembler.assemble(viewerId, posts);
        Post first = posts.get(0);
        Post last = posts.get(posts.size() - 1);
        String startCursor = encodeCursor(first.getCreatedAt(), first.getId(), scope);
        String endCursor = encodeCursor(last.getCreatedAt(), last.getId(), scope);
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<FeedPostResponse> getFeed(UUID viewerId, String cursor, int size) {
        // Decoded before the empty-follow-set short-circuit below so a malformed cursor is
        // rejected the same way regardless of how many accounts the viewer follows, instead of
        // silently returning an empty page for a viewer who follows nobody.
        Cursor decoded = decodeCursor(cursor, CursorScope.POST_FEED);
        List<UUID> authorIds = socialService.getAcceptedFollowingExcludingBlocks(viewerId);
        int pageSize = normalizeLimit(size);
        if (authorIds.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Post> posts =
                decoded == null
                        ? postRepository.findFirstFeedPosts(authorIds, page)
                        : postRepository.findFeedPostsBefore(
                                authorIds,
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
        List<FeedPostResponse> content = postResponseAssembler.assembleFeed(viewerId, posts);
        Post first = posts.get(0);
        Post last = posts.get(posts.size() - 1);
        String startCursor =
                encodeCursor(first.getCreatedAt(), first.getId(), CursorScope.POST_FEED);
        String endCursor = encodeCursor(last.getCreatedAt(), last.getId(), CursorScope.POST_FEED);
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
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
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

    // Soft delete keeps the row (GLOBAL_RULES soft delete policy): status flip + deleted_at; the
    // trg_post_count trigger adjusts users.post_count on the same UPDATE statement.
    private void softDelete(Post post) {
        post.setStatus(PostStatus.REMOVED);
        post.setDeletedAt(OffsetDateTime.now());
        hashtagService.removeHashtagsForPost(post.getId());
        enqueuePostIndexDelete(post);
    }

    private void validateMediaCardinality(
            PostType postType, List<UUID> mediaIds, Map<UUID, MediaAsset> assets) {
        if (postType == PostType.CAROUSEL) {
            if (mediaIds.size() < 2) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Carousel posts require at least 2 media items");
            }
            long maxItems = systemSettingService.getRequiredLong(MAX_POST_MEDIA_ITEMS);
            if (mediaIds.size() > maxItems) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST,
                        "Carousel exceeds the maximum of " + maxItems + " media items");
            }
            return;
        }
        if (mediaIds.size() != 1) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Image and video posts require exactly 1 media item");
        }
        MediaAsset asset = assets.get(mediaIds.get(0));
        MediaType expected = postType == PostType.IMAGE ? MediaType.IMAGE : MediaType.VIDEO;
        if (asset.getMediaType() != expected) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Media asset type does not match the post type");
        }
    }

    private void upsertCaptionHashtags(UUID postId, String caption) {
        List<String> tags = extractHashtags(caption);
        if (!tags.isEmpty()) {
            hashtagService.upsertHashtagsForPost(postId, tags);
        }
    }

    /**
     * Refuses a caption naming a banned hashtag, before the caller mutates anything.
     *
     * <p>Called on every write path that could put a tag into {@code post_hashtags}, including the
     * draft ones. A draft carrying a banned tag could never be published, so refusing it at the
     * point it is written tells the author while the caption is still in front of them rather than
     * at publish time. The check reads the caption being submitted, so removing the offending tag
     * and retrying always succeeds; it never traps an author in a post it cannot edit.
     */
    private void rejectBannedHashtags(String caption) {
        List<String> tags = extractHashtags(caption);
        if (tags.isEmpty()) {
            return;
        }
        List<String> banned = hashtagService.findBannedNames(tags);
        if (!banned.isEmpty()) {
            throw new AppException(ApiErrorCode.POST_BANNED_HASHTAG, Map.of("bannedTags", banned));
        }
    }

    private PostModerationProjection requireModerationView(UUID postId) {
        return postRepository
                .findModerationViewIncludingDeleted(postId)
                .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
    }

    private void enqueuePostIndexUpsert(Post post) {
        enqueuePostIndexUpsert(post.getId(), post.getUserId(), post.getCreatedAt());
    }

    private void enqueuePostIndexDelete(Post post) {
        enqueuePostIndexDelete(post.getId(), post.getUserId());
    }

    // Must run after upsertCaptionHashtags so the post_hashtags associations are queryable here.
    private void enqueuePostIndexUpsert(UUID postId, UUID userId, OffsetDateTime createdAt) {
        List<String> hashtagIds =
                hashtagService
                        .getHashtagIdsForPosts(List.of(postId))
                        .getOrDefault(postId, List.of())
                        .stream()
                        .map(UUID::toString)
                        .toList();
        // User free-text (caption) is excluded from the payload; the consumer reads it from the
        // source-of-truth post row it already loads for the Q4 gate.
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId.toString());
        data.put("userId", userId.toString());
        data.put("status", "published");
        data.put("hashtagIds", hashtagIds);
        data.put("createdAt", createdAt.toString());
        outboxService.enqueue(
                PostEventTypes.POST_INDEX_UPSERT_V1,
                PostEventTypes.POST_INDEX_UPSERT_V1,
                "post",
                postId,
                userId,
                data);
    }

    private void enqueuePostIndexDelete(UUID postId, UUID userId) {
        outboxService.enqueue(
                PostEventTypes.POST_INDEX_DELETE_V1,
                PostEventTypes.POST_INDEX_DELETE_V1,
                "post",
                postId,
                userId,
                Map.of("postId", postId.toString()));
    }

    // Tokens are #-prefixed runs of Unicode letters, digits, and underscores; normalization and
    // case-insensitive de-duplication are HashtagService's responsibility.
    private List<String> extractHashtags(String caption) {
        if (caption == null || caption.isBlank()) {
            return List.of();
        }
        long maxTags = systemSettingService.getRequiredLong(MAX_HASHTAGS_PER_POST);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher matcher = HASHTAG_PATTERN.matcher(caption);
        while (matcher.find() && tags.size() < maxTags) {
            tags.add(matcher.group(1));
        }
        return List.copyOf(tags);
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
