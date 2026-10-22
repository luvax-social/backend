package com.app.modules.post.service.impl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.post.dto.request.PostTagInput;
import com.app.modules.post.dto.response.PostMediaTagResponse;
import com.app.modules.post.dto.response.PostPreviewResponse;
import com.app.modules.post.dto.response.TagRequestResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.entity.PostUserTag;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.TagStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.repository.PostUserTagRepository;
import com.app.modules.post.service.PostPreviewService;
import com.app.modules.post.service.PostTagService;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.service.UserSummaryService;

@Service
public class PostTagServiceImpl implements PostTagService {

    private static final String MAX_TAGS_PER_MEDIA = "max_post_tags_per_media";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PostUserTagRepository tagRepository;
    private final PostRepository postRepository;
    private final PostUserRepository postUserRepository;
    private final InteractionPolicyService interactionPolicyService;
    private final SystemSettingService systemSettingService;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;
    private final SocialService socialService;
    private final PostPreviewService postPreviewService;
    private final PostVisibilityService postVisibilityService;

    public PostTagServiceImpl(
            PostUserTagRepository tagRepository,
            PostRepository postRepository,
            PostUserRepository postUserRepository,
            InteractionPolicyService interactionPolicyService,
            SystemSettingService systemSettingService,
            OutboxService outboxService,
            UserSummaryService userSummaryService,
            SocialService socialService,
            PostPreviewService postPreviewService,
            PostVisibilityService postVisibilityService) {
        this.tagRepository = tagRepository;
        this.postRepository = postRepository;
        this.postUserRepository = postUserRepository;
        this.interactionPolicyService = interactionPolicyService;
        this.systemSettingService = systemSettingService;
        this.outboxService = outboxService;
        this.userSummaryService = userSummaryService;
        this.socialService = socialService;
        this.postPreviewService = postPreviewService;
        this.postVisibilityService = postVisibilityService;
    }

    @Override
    @Transactional
    public void replaceTags(Post post, List<PostTagInput> inputs) {
        List<PostTagInput> requested = inputs == null ? List.of() : inputs;
        List<PostMedia> media = post.getMedia();
        long perMediaLimit = systemSettingService.getRequiredLong(MAX_TAGS_PER_MEDIA);
        Map<UUID, Map<UUID, PostTagInput>> inputByMediaAndUser = new HashMap<>();
        for (PostTagInput input : requested) {
            if (input.mediaPosition() >= media.size()) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "A tag names a media item the post lacks");
            }
            UUID mediaId = media.get(input.mediaPosition()).getId();
            Map<UUID, PostTagInput> forMedia =
                    inputByMediaAndUser.computeIfAbsent(mediaId, id -> new HashMap<>());
            if (forMedia.put(input.userId(), input) != null) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "A person can be tagged once on an item");
            }
            if (forMedia.size() > perMediaLimit) {
                throw new AppException(ApiErrorCode.POST_TAG_LIMIT_REACHED);
            }
        }
        Map<UUID, Map<UUID, PostUserTag>> existing = new HashMap<>();
        for (PostUserTag tag : tagRepository.findByPostId(post.getId())) {
            existing.computeIfAbsent(tag.getPostMediaId(), id -> new HashMap<>())
                    .put(tag.getTaggedUserId(), tag);
        }
        boolean announce = post.getStatus() == PostStatus.PUBLISHED;
        Set<UUID> taggedIds =
                requested.stream().map(PostTagInput::userId).collect(Collectors.toSet());
        Map<UUID, User> activeUsers = loadActiveUsers(taggedIds);

        for (Map.Entry<UUID, Map<UUID, PostUserTag>> mediaEntry : existing.entrySet()) {
            Map<UUID, PostTagInput> wanted =
                    inputByMediaAndUser.getOrDefault(mediaEntry.getKey(), Map.of());
            for (PostUserTag tag : mediaEntry.getValue().values()) {
                if (!wanted.containsKey(tag.getTaggedUserId())) {
                    tagRepository.delete(tag);
                    if (announce) {
                        enqueueRemoved(post, tag, "owner");
                    }
                }
            }
        }
        // Flushed before inserting so a person removed from an item and tagged on it again in the
        // same request does not collide with the row that is about to go.
        tagRepository.flush();

        for (Map.Entry<UUID, Map<UUID, PostTagInput>> mediaEntry : inputByMediaAndUser.entrySet()) {
            UUID mediaId = mediaEntry.getKey();
            for (PostTagInput input : mediaEntry.getValue().values()) {
                PostUserTag kept = existing.getOrDefault(mediaId, Map.of()).get(input.userId());
                if (kept != null) {
                    kept.setXPosition(scale(input.x()));
                    kept.setYPosition(scale(input.y()));
                    continue;
                }
                if (!activeUsers.containsKey(input.userId())) {
                    throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
                }
                InteractionDecision decision = decide(post.getUserId(), input.userId());
                requireNotDenied(decision);
                PostUserTag created =
                        tagRepository.save(
                                PostUserTag.builder()
                                        .postId(post.getId())
                                        .postMediaId(mediaId)
                                        .taggedUserId(input.userId())
                                        .xPosition(scale(input.x()))
                                        .yPosition(scale(input.y()))
                                        .status(statusFor(decision))
                                        .createdBy(post.getUserId())
                                        .build());
                if (created.getStatus() == TagStatus.APPROVED) {
                    created.setApprovedAt(OffsetDateTime.now());
                }
                if (announce) {
                    enqueueCreated(post, created, decision);
                }
            }
        }
        tagRepository.flush();
    }

    @Override
    @Transactional
    public void announceOnPublication(Post post) {
        List<PostUserTag> tags = tagRepository.findByPostId(post.getId());
        if (tags.isEmpty()) {
            return;
        }
        Map<UUID, User> activeUsers =
                loadActiveUsers(
                        tags.stream()
                                .map(PostUserTag::getTaggedUserId)
                                .collect(Collectors.toSet()));
        for (PostUserTag tag : tags) {
            InteractionDecision decision =
                    activeUsers.containsKey(tag.getTaggedUserId())
                            ? decide(post.getUserId(), tag.getTaggedUserId())
                            : new InteractionDecision(
                                    InteractionOutcome.DENY, InteractionReason.BLOCKED);
            if (decision.denied()) {
                tagRepository.delete(tag);
                continue;
            }
            TagStatus status = statusFor(decision);
            // An approval the tagged person already gave stands; only an undecided tag takes the
            // outcome the policy gives now.
            if (tag.getStatus() != TagStatus.APPROVED) {
                tag.setStatus(status);
                if (status == TagStatus.APPROVED) {
                    tag.setApprovedAt(OffsetDateTime.now());
                }
            }
            enqueueCreated(post, tag, decision);
        }
        tagRepository.flush();
    }

    @Override
    @Transactional
    public void approve(UUID viewerId, UUID postId, UUID tagId) {
        PostUserTag tag = requireVisibleTag(viewerId, postId, tagId);
        if (!viewerId.equals(tag.getTaggedUserId())) {
            throw new AppException(ApiErrorCode.POST_TAG_NOT_FOUND);
        }
        if (tag.getStatus() == TagStatus.APPROVED) {
            return;
        }
        tag.setStatus(TagStatus.APPROVED);
        tag.setApprovedAt(OffsetDateTime.now());
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId.toString());
        data.put("tagId", tagId.toString());
        data.put("taggedUserId", viewerId.toString());
        outboxService.enqueue(
                PostEventTypes.POST_TAG_APPROVED_V1,
                PostEventTypes.POST_TAG_APPROVED_V1,
                "post",
                postId,
                viewerId,
                data);
    }

    @Override
    @Transactional
    public void remove(UUID requesterId, UUID postId, UUID tagId) {
        PostUserTag tag =
                tagRepository
                        .findByIdAndPostId(tagId, postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_TAG_NOT_FOUND));
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_TAG_NOT_FOUND));
        boolean isOwner = requesterId.equals(post.getUserId());
        boolean isTagged = requesterId.equals(tag.getTaggedUserId());
        if (!isOwner && !isTagged) {
            throw new AppException(ApiErrorCode.POST_TAG_NOT_FOUND);
        }
        tagRepository.delete(tag);
        if (post.getStatus() == PostStatus.PUBLISHED) {
            enqueueRemoved(post, tag, isOwner ? "owner" : "tagged");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<TagRequestResponse> listPending(
            UUID viewerId, String cursor, int size) {
        int pageSize = size > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (size < 1 ? DEFAULT_PAGE_SIZE : size);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.TAG_REQUESTS);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<PostUserTag> rows =
                decoded == null
                        ? tagRepository.findFirstPendingForUser(viewerId, page)
                        : tagRepository.findPendingForUserBefore(
                                viewerId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        boolean hasNextPage = rows.size() > pageSize;
        if (hasNextPage) {
            rows = rows.subList(0, pageSize);
        }
        if (rows.isEmpty()) {
            return CursorPageResponse.of(List.of(), false, null, null, cursor != null);
        }
        List<UUID> postIds = rows.stream().map(PostUserTag::getPostId).distinct().toList();
        Map<UUID, Post> posts = new HashMap<>();
        for (Post post : postRepository.findAllById(postIds)) {
            posts.put(post.getId(), post);
        }
        Map<UUID, UserSummaryResponse> owners =
                userSummaryService.loadSummaries(
                        posts.values().stream().map(Post::getUserId).distinct().toList());
        Map<UUID, PostPreviewResponse> previews =
                postPreviewService.loadPreviews(viewerId, postIds);
        List<TagRequestResponse> content = new ArrayList<>(rows.size());
        for (PostUserTag row : rows) {
            Post post = posts.get(row.getPostId());
            PostPreviewResponse preview = previews.get(row.getPostId());
            // A request on a post the viewer can no longer open is not offered: approving it would
            // show a tag on a post the viewer cannot see.
            if (post == null || preview == null || !preview.available()) {
                continue;
            }
            content.add(
                    new TagRequestResponse(
                            row.getId(),
                            row.getPostId(),
                            owners.get(post.getUserId()),
                            preview.media(),
                            row.getCreatedAt()));
        }
        PostUserTag first = rows.get(0);
        PostUserTag last = rows.get(rows.size() - 1);
        return CursorPageResponse.of(
                content, hasNextPage, encode(first), encode(last), cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PostMediaTagResponse>> loadForPosts(
            UUID viewerId, Collection<Post> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> ownerByPost = new HashMap<>();
        for (Post post : posts) {
            ownerByPost.put(post.getId(), post.getUserId());
        }
        List<PostUserTag> rows = tagRepository.findByPostIdIn(ownerByPost.keySet());
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<UUID> blocked = socialService.findBlockedEitherDirection(viewerId);
        Map<UUID, UserSummaryResponse> users =
                userSummaryService.loadSummaries(
                        rows.stream().map(PostUserTag::getTaggedUserId).distinct().toList());
        Map<UUID, List<PostMediaTagResponse>> byMedia = new HashMap<>();
        for (PostUserTag row : rows) {
            boolean viewerIsOwner = viewerId.equals(ownerByPost.get(row.getPostId()));
            boolean viewerIsTagged = viewerId.equals(row.getTaggedUserId());
            if (row.getStatus() == TagStatus.PENDING && !viewerIsOwner && !viewerIsTagged) {
                continue;
            }
            UserSummaryResponse user = users.get(row.getTaggedUserId());
            if (user == null || user.username() == null) {
                continue;
            }
            if (!viewerIsTagged && blocked.contains(row.getTaggedUserId())) {
                continue;
            }
            byMedia.computeIfAbsent(row.getPostMediaId(), id -> new ArrayList<>())
                    .add(
                            new PostMediaTagResponse(
                                    row.getId(),
                                    user,
                                    row.getXPosition(),
                                    row.getYPosition(),
                                    row.getStatus()));
        }
        return byMedia;
    }

    private PostUserTag requireVisibleTag(UUID viewerId, UUID postId, UUID tagId) {
        PostUserTag tag =
                tagRepository
                        .findByIdAndPostId(tagId, postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_TAG_NOT_FOUND));
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_TAG_NOT_FOUND));
        if (post.getStatus() != PostStatus.PUBLISHED
                || !postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.POST_TAG_NOT_FOUND);
        }
        return tag;
    }

    private InteractionDecision decide(UUID ownerId, UUID taggedUserId) {
        return interactionPolicyService.evaluate(
                new InteractionRequest(
                        ownerId, taggedUserId, InteractionType.TAG, null, List.of(), null));
    }

    // A block answers as an unknown person, so the owner cannot tell it from a username that does
    // not exist; any other refusal is the tagged person's own setting and says so.
    private static void requireNotDenied(InteractionDecision decision) {
        if (!decision.denied()) {
            return;
        }
        if (decision.reason() == InteractionReason.BLOCKED) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
        }
        throw new AppException(ApiErrorCode.POST_TAG_NOT_ALLOWED);
    }

    private static TagStatus statusFor(InteractionDecision decision) {
        return decision.outcome() == InteractionOutcome.ALLOW
                ? TagStatus.APPROVED
                : TagStatus.PENDING;
    }

    private Map<UUID, User> loadActiveUsers(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return postUserRepository.findAllByIdInAndDeletedAtIsNull(ids).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .collect(Collectors.toMap(User::getId, user -> user));
    }

    private void enqueueCreated(Post post, PostUserTag tag, InteractionDecision decision) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("tagId", tag.getId().toString());
        data.put("taggedUserId", tag.getTaggedUserId().toString());
        data.put("outcome", tag.getStatus().toJson());
        // A tag a limit hid is stored and counted but never announced to the tagged person, and
        // the owner tagging themselves needs no notice.
        data.put(
                "notify",
                decision.outcome() != InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS
                        && !tag.getTaggedUserId().equals(post.getUserId()));
        outboxService.enqueue(
                PostEventTypes.POST_TAG_CREATED_V1,
                PostEventTypes.POST_TAG_CREATED_V1,
                "post",
                post.getId(),
                post.getUserId(),
                data);
    }

    private void enqueueRemoved(Post post, PostUserTag tag, String removedBy) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("tagId", tag.getId().toString());
        data.put("taggedUserId", tag.getTaggedUserId().toString());
        data.put("removedBy", removedBy);
        UUID actor = "owner".equals(removedBy) ? post.getUserId() : tag.getTaggedUserId();
        outboxService.enqueue(
                PostEventTypes.POST_TAG_REMOVED_V1,
                PostEventTypes.POST_TAG_REMOVED_V1,
                "post",
                post.getId(),
                actor,
                data);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static String encode(PostUserTag row) {
        if (row.getCreatedAt() == null) {
            return null;
        }
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(row.getCreatedAt()), row.getId()),
                CursorScope.TAG_REQUESTS);
    }
}
