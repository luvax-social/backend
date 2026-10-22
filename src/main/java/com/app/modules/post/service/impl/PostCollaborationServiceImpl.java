package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import com.app.modules.post.dto.response.CollaborationInviteResponse;
import com.app.modules.post.dto.response.PostCollaboratorResponse;
import com.app.modules.post.dto.response.PostPreviewResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.entity.PostCollaboratorId;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostPreviewService;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.service.UserSummaryService;

@Service
public class PostCollaborationServiceImpl implements PostCollaborationService {

    private static final String MAX_COLLABORATORS = "max_post_collaborators";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @PersistenceContext private EntityManager entityManager;

    private final PostCollaboratorRepository collaboratorRepository;
    private final PostRepository postRepository;
    private final PostUserRepository postUserRepository;
    private final InteractionPolicyService interactionPolicyService;
    private final SystemSettingService systemSettingService;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;
    private final PostPreviewService postPreviewService;
    private final PostIndexEventPublisher postIndexEventPublisher;

    public PostCollaborationServiceImpl(
            PostCollaboratorRepository collaboratorRepository,
            PostRepository postRepository,
            PostUserRepository postUserRepository,
            InteractionPolicyService interactionPolicyService,
            SystemSettingService systemSettingService,
            OutboxService outboxService,
            UserSummaryService userSummaryService,
            PostPreviewService postPreviewService,
            PostIndexEventPublisher postIndexEventPublisher) {
        this.collaboratorRepository = collaboratorRepository;
        this.postRepository = postRepository;
        this.postUserRepository = postUserRepository;
        this.interactionPolicyService = interactionPolicyService;
        this.systemSettingService = systemSettingService;
        this.outboxService = outboxService;
        this.userSummaryService = userSummaryService;
        this.postPreviewService = postPreviewService;
        this.postIndexEventPublisher = postIndexEventPublisher;
    }

    @Override
    @Transactional
    public void replaceInvites(Post post, List<UUID> userIds) {
        List<UUID> requested = userIds == null ? List.of() : userIds;
        UUID ownerId = post.getUserId();
        Set<UUID> wanted = new HashSet<>(requested);
        if (wanted.size() != requested.size()) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Duplicate co-author ids");
        }
        if (wanted.contains(ownerId)) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "The post owner cannot be invited as a co-author");
        }
        long max = systemSettingService.getRequiredLong(MAX_COLLABORATORS);
        if (wanted.size() > max) {
            throw new AppException(ApiErrorCode.POST_COLLABORATOR_LIMIT_REACHED);
        }
        Map<UUID, PostCollaborator> existing = new HashMap<>();
        for (PostCollaborator row : collaboratorRepository.findByPostId(post.getId())) {
            existing.put(row.getUserId(), row);
        }
        Map<UUID, User> activeUsers = loadActiveUsers(wanted);
        boolean announce = post.getStatus() == PostStatus.PUBLISHED;
        boolean authorsChanged = false;

        for (PostCollaborator row : existing.values()) {
            if (row.getStatus().isLive() && !wanted.contains(row.getUserId())) {
                authorsChanged |= row.getStatus() == CollaborationStatus.ACCEPTED;
                row.setStatus(CollaborationStatus.REMOVED);
                row.setRespondedAt(OffsetDateTime.now());
            }
        }
        // Flushed before inviting so the removal frees its slot ahead of the trigger that caps the
        // live rows of a post.
        collaboratorRepository.flush();

        for (UUID userId : requested) {
            PostCollaborator row = existing.get(userId);
            if (row != null && row.getStatus().isLive()) {
                continue;
            }
            if (!activeUsers.containsKey(userId)) {
                throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
            }
            // A block answers as an unknown account, so the owner cannot tell it from a username
            // that does not exist.
            InteractionDecision decision =
                    interactionPolicyService.evaluate(
                            new InteractionRequest(
                                    ownerId,
                                    userId,
                                    InteractionType.COLLABORATION_INVITE,
                                    null,
                                    List.of(),
                                    null));
            if (decision.denied()) {
                throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
            }
            if (row == null) {
                collaboratorRepository.saveAndFlush(
                        PostCollaborator.builder()
                                .id(new PostCollaboratorId(post.getId(), userId))
                                .status(CollaborationStatus.INVITED)
                                .invitedBy(ownerId)
                                .build());
            } else {
                collaboratorRepository.reinvite(post.getId(), userId, ownerId);
                entityManager.refresh(row);
            }
            if (announce) {
                enqueueInvited(post, userId);
            }
        }
        if (authorsChanged && announce) {
            postIndexEventPublisher.enqueueUpsert(post);
        }
    }

    @Override
    @Transactional
    public void announceOnPublication(Post post) {
        List<PostCollaborator> invited =
                collaboratorRepository.findByPostId(post.getId()).stream()
                        .filter(row -> row.getStatus() == CollaborationStatus.INVITED)
                        .toList();
        if (invited.isEmpty()) {
            return;
        }
        Map<UUID, User> activeUsers =
                loadActiveUsers(
                        invited.stream()
                                .map(PostCollaborator::getUserId)
                                .collect(Collectors.toSet()));
        for (PostCollaborator row : invited) {
            boolean refused =
                    !activeUsers.containsKey(row.getUserId())
                            || interactionPolicyService
                                    .evaluate(
                                            new InteractionRequest(
                                                    post.getUserId(),
                                                    row.getUserId(),
                                                    InteractionType.COLLABORATION_INVITE,
                                                    null,
                                                    List.of(),
                                                    null))
                                    .denied();
            if (refused) {
                row.setStatus(CollaborationStatus.REMOVED);
                row.setRespondedAt(OffsetDateTime.now());
                continue;
            }
            enqueueInvited(post, row.getUserId());
        }
        collaboratorRepository.flush();
    }

    @Override
    @Transactional
    public void accept(UUID viewerId, UUID postId) {
        Post post = requirePublishedPost(postId);
        PostCollaborator row = requireRow(postId, viewerId, CollaborationStatus.INVITED);
        // The invitation may predate a block between the two accounts.
        if (interactionPolicyService
                .evaluate(
                        new InteractionRequest(
                                post.getUserId(),
                                viewerId,
                                InteractionType.COLLABORATION_INVITE,
                                null,
                                List.of(),
                                null))
                .denied()) {
            throw new AppException(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
        }
        OffsetDateTime now = OffsetDateTime.now();
        row.setStatus(CollaborationStatus.ACCEPTED);
        row.setRespondedAt(now);
        row.setAcceptedAt(now);
        collaboratorRepository.flush();
        enqueueResponse(PostEventTypes.POST_COLLABORATION_ACCEPTED_V1, post, viewerId);
        // One document per post: the author list it carries changes, nothing else does.
        postIndexEventPublisher.enqueueUpsert(post);
    }

    @Override
    @Transactional
    public void decline(UUID viewerId, UUID postId) {
        Post post = requirePublishedPost(postId);
        PostCollaborator row = requireRow(postId, viewerId, CollaborationStatus.INVITED);
        row.setStatus(CollaborationStatus.DECLINED);
        row.setRespondedAt(OffsetDateTime.now());
        collaboratorRepository.flush();
        enqueueResponse(PostEventTypes.POST_COLLABORATION_DECLINED_V1, post, viewerId);
    }

    @Override
    @Transactional
    public void leave(UUID viewerId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.POST_COLLABORATION_NOT_FOUND));
        PostCollaborator row = requireRow(postId, viewerId, CollaborationStatus.ACCEPTED);
        // accepted_at is kept: the post's performance is still attributed to the account that held
        // a share of it.
        row.setStatus(CollaborationStatus.LEFT);
        row.setRespondedAt(OffsetDateTime.now());
        collaboratorRepository.flush();
        enqueueResponse(PostEventTypes.POST_COLLABORATION_LEFT_V1, post, viewerId);
        if (post.getStatus() == PostStatus.PUBLISHED) {
            postIndexEventPublisher.enqueueUpsert(post);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<CollaborationInviteResponse> listInvites(
            UUID viewerId, String cursor, int size) {
        int pageSize = size > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (size < 1 ? DEFAULT_PAGE_SIZE : size);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.COLLABORATION_INVITES);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<PostCollaborator> rows =
                decoded == null
                        ? collaboratorRepository.findFirstInvitesForUser(viewerId, page)
                        : collaboratorRepository.findInvitesForUserBefore(
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
        List<UUID> postIds = rows.stream().map(PostCollaborator::getPostId).toList();
        Map<UUID, PostPreviewResponse> previews =
                postPreviewService.loadPreviews(viewerId, postIds);
        Map<UUID, UserSummaryResponse> inviters =
                userSummaryService.loadSummaries(
                        rows.stream()
                                .map(PostCollaborator::getInvitedBy)
                                .filter(id -> id != null)
                                .distinct()
                                .toList());
        List<CollaborationInviteResponse> content = new ArrayList<>(rows.size());
        for (PostCollaborator row : rows) {
            PostPreviewResponse preview = previews.get(row.getPostId());
            // An invitation to a post the viewer cannot open is not offered.
            if (preview == null || !preview.available()) {
                continue;
            }
            content.add(
                    new CollaborationInviteResponse(
                            row.getPostId(),
                            inviters.get(row.getInvitedBy()),
                            preview.media(),
                            row.getInvitedAt()));
        }
        PostCollaborator first = rows.get(0);
        PostCollaborator last = rows.get(rows.size() - 1);
        return CursorPageResponse.of(
                content, hasNextPage, encode(first), encode(last), cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CollaborationView> loadForPosts(UUID viewerId, Collection<Post> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> ownerByPost = new HashMap<>();
        for (Post post : posts) {
            ownerByPost.put(post.getId(), post.getUserId());
        }
        List<PostCollaborator> rows = collaboratorRepository.findByPostIdIn(ownerByPost.keySet());
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UserSummaryResponse> users =
                userSummaryService.loadSummaries(
                        rows.stream().map(PostCollaborator::getUserId).distinct().toList());
        Map<UUID, List<PostCollaboratorResponse>> shown = new HashMap<>();
        Map<UUID, CollaborationStatus> viewerStatus = new HashMap<>();
        for (PostCollaborator row : rows) {
            CollaborationStatus status = row.getStatus();
            boolean viewerIsInvitee = viewerId.equals(row.getUserId());
            if (viewerIsInvitee && status.isLive()) {
                viewerStatus.put(row.getPostId(), status);
            }
            boolean viewerIsOwner = viewerId.equals(ownerByPost.get(row.getPostId()));
            boolean visible =
                    status == CollaborationStatus.ACCEPTED
                            || (status == CollaborationStatus.INVITED
                                    && (viewerIsOwner || viewerIsInvitee));
            UserSummaryResponse user = users.get(row.getUserId());
            if (!visible || user == null || user.username() == null) {
                continue;
            }
            shown.computeIfAbsent(row.getPostId(), id -> new ArrayList<>())
                    .add(new PostCollaboratorResponse(user, status));
        }
        Map<UUID, CollaborationView> result = new HashMap<>();
        Set<UUID> postIds = new HashSet<>(shown.keySet());
        postIds.addAll(viewerStatus.keySet());
        for (UUID postId : postIds) {
            result.put(
                    postId,
                    new CollaborationView(
                            shown.getOrDefault(postId, List.of()), viewerStatus.get(postId)));
        }
        return result;
    }

    private Post requirePublishedPost(UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.POST_COLLABORATION_NOT_FOUND));
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
        }
        return post;
    }

    private PostCollaborator requireRow(UUID postId, UUID userId, CollaborationStatus expected) {
        PostCollaborator row =
                collaboratorRepository
                        .find(postId, userId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.POST_COLLABORATION_NOT_FOUND));
        if (row.getStatus() != expected) {
            throw new AppException(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
        }
        return row;
    }

    private Map<UUID, User> loadActiveUsers(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return postUserRepository.findAllByIdInAndDeletedAtIsNull(ids).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .collect(Collectors.toMap(User::getId, user -> user));
    }

    private void enqueueInvited(Post post, UUID inviteeId) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("inviteeId", inviteeId.toString());
        outboxService.enqueue(
                PostEventTypes.POST_COLLABORATION_INVITED_V1,
                PostEventTypes.POST_COLLABORATION_INVITED_V1,
                "post",
                post.getId(),
                post.getUserId(),
                data);
    }

    private void enqueueResponse(String eventType, Post post, UUID collaboratorId) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("collaboratorId", collaboratorId.toString());
        data.put("ownerId", post.getUserId().toString());
        outboxService.enqueue(eventType, eventType, "post", post.getId(), collaboratorId, data);
    }

    private static String encode(PostCollaborator row) {
        if (row.getInvitedAt() == null) {
            return null;
        }
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(row.getInvitedAt()), row.getPostId()),
                CursorScope.COLLABORATION_INVITES);
    }
}
