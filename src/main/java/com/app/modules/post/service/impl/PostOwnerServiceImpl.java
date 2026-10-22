package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
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
import com.app.modules.post.dto.request.ReplaceCollaboratorsRequest;
import com.app.modules.post.dto.request.ReplacePostTagsRequest;
import com.app.modules.post.dto.request.SetPostPlaceRequest;
import com.app.modules.post.dto.request.UpdateAltTextRequest;
import com.app.modules.post.dto.request.UpdatePostContentRequest;
import com.app.modules.post.dto.response.OwnerPostCountsResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.repository.PostMediaRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostOwnerService;
import com.app.modules.post.service.PostTagService;

@Service
public class PostOwnerServiceImpl implements PostOwnerService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final PostContentWriter postContentWriter;
    private final PostResponseAssembler postResponseAssembler;
    private final PostSchedulePolicy postSchedulePolicy;
    private final PostTagService postTagService;
    private final PostCollaborationService postCollaborationService;
    private final PlaceService placeService;
    private final PostIndexEventPublisher postIndexEventPublisher;
    private final OutboxService outboxService;

    public PostOwnerServiceImpl(
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            PostContentWriter postContentWriter,
            PostResponseAssembler postResponseAssembler,
            PostSchedulePolicy postSchedulePolicy,
            PostTagService postTagService,
            PostCollaborationService postCollaborationService,
            PlaceService placeService,
            PostIndexEventPublisher postIndexEventPublisher,
            OutboxService outboxService) {
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.postContentWriter = postContentWriter;
        this.postResponseAssembler = postResponseAssembler;
        this.postSchedulePolicy = postSchedulePolicy;
        this.postTagService = postTagService;
        this.postCollaborationService = postCollaborationService;
        this.placeService = placeService;
        this.postIndexEventPublisher = postIndexEventPublisher;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public PostResponse replaceContent(
            UUID ownerId, UUID postId, UpdatePostContentRequest request) {
        Post post = requireOwned(ownerId, postId);
        if (post.getStatus() != PostStatus.DRAFT && post.getStatus() != PostStatus.SCHEDULED) {
            throw new AppException(ApiErrorCode.POST_STATE_CONFLICT);
        }
        postContentWriter.replace(post, PostContentWriter.Content.from(request));
        if (post.getStatus() == PostStatus.DRAFT) {
            outboxService.enqueue(
                    PostEventTypes.POST_DRAFT_SAVED_V1,
                    PostEventTypes.POST_DRAFT_SAVED_V1,
                    "post",
                    post.getId(),
                    ownerId,
                    java.util.Map.of("postId", post.getId().toString()));
        }
        return postResponseAssembler.assemble(ownerId, post);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PostResponse> listOwnerPosts(
            UUID ownerId, PostStatus status, String cursor, int size) {
        if (status != PostStatus.DRAFT
                && status != PostStatus.SCHEDULED
                && status != PostStatus.ARCHIVED) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "status must be draft, scheduled or archived");
        }
        int pageSize = size > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (size < 1 ? DEFAULT_PAGE_SIZE : size);
        String scope = scopeFor(status);
        Cursor decoded = CursorCodec.decode(cursor, scope);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Post> posts;
        if (status == PostStatus.SCHEDULED) {
            posts =
                    decoded == null
                            ? postRepository.findFirstOwnerScheduledPosts(ownerId, page)
                            : postRepository.findOwnerScheduledPostsAfter(
                                    ownerId,
                                    TimeCursors.fromMicros(decoded.sortValueMicros()),
                                    decoded.id(),
                                    page);
        } else {
            posts =
                    decoded == null
                            ? postRepository.findFirstOwnerPostsByStatus(
                                    ownerId, status.toJson(), page)
                            : postRepository.findOwnerPostsByStatusBefore(
                                    ownerId,
                                    status.toJson(),
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
        Post first = posts.get(0);
        Post last = posts.get(posts.size() - 1);
        return CursorPageResponse.of(
                postResponseAssembler.assemble(ownerId, posts),
                hasNextPage,
                encode(first, status, scope),
                encode(last, status, scope),
                cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerPostCountsResponse counts(UUID ownerId) {
        return new OwnerPostCountsResponse(
                postRepository.countByOwnerAndStatus(ownerId, PostStatus.DRAFT.toJson()),
                postRepository.countByOwnerAndStatus(ownerId, PostStatus.SCHEDULED.toJson()),
                postRepository.countByOwnerAndStatus(ownerId, PostStatus.ARCHIVED.toJson()),
                postSchedulePolicy.limit());
    }

    @Override
    @Transactional
    public PostResponse setPlace(UUID ownerId, UUID postId, SetPostPlaceRequest request) {
        Post post = requireOwned(ownerId, postId);
        Place place =
                placeService.resolveForPostWrite(ownerId, request.placeId(), request.placeName());
        post.setPlaceId(place == null ? null : place.getId());
        postRepository.saveAndFlush(post);
        if (post.getStatus() == PostStatus.PUBLISHED) {
            // The place name is part of the indexed document.
            postIndexEventPublisher.enqueueUpsert(post);
        }
        return postResponseAssembler.assemble(ownerId, post);
    }

    @Override
    @Transactional
    public PostResponse replaceTags(UUID ownerId, UUID postId, ReplacePostTagsRequest request) {
        Post post = requireOwned(ownerId, postId);
        postTagService.replaceTags(post, request.tags());
        return postResponseAssembler.assemble(ownerId, post);
    }

    @Override
    @Transactional
    public PostResponse replaceCollaborators(
            UUID ownerId, UUID postId, ReplaceCollaboratorsRequest request) {
        Post post = requireOwned(ownerId, postId);
        postCollaborationService.replaceInvites(post, request.userIds());
        return postResponseAssembler.assemble(ownerId, post);
    }

    @Override
    @Transactional
    public PostResponse updateAltText(
            UUID ownerId, UUID postId, UUID postMediaId, UpdateAltTextRequest request) {
        Post post = requireOwned(ownerId, postId);
        PostMedia media =
                postMediaRepository
                        .findByIdAndPostId(postMediaId, postId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.NOT_FOUND, "Media not found"));
        media.setAltText(PostContentWriter.normaliseAltText(request.altText()));
        postMediaRepository.saveAndFlush(media);
        return postResponseAssembler.assemble(ownerId, post);
    }

    // Row-locked so the owner's edit, a reschedule and a due publication serialise. A requester
    // who is not the owner learns nothing about an unpublished post.
    private Post requireOwned(UUID ownerId, UUID postId) {
        Post post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (!ownerId.equals(post.getUserId())) {
            throw new AppException(
                    post.getStatus() == PostStatus.PUBLISHED
                            ? ApiErrorCode.POST_FORBIDDEN
                            : ApiErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private static String scopeFor(PostStatus status) {
        return switch (status) {
            case DRAFT -> CursorScope.OWNER_POSTS_DRAFT;
            case SCHEDULED -> CursorScope.OWNER_POSTS_SCHEDULED;
            default -> CursorScope.OWNER_POSTS_ARCHIVED;
        };
    }

    private static String encode(Post post, PostStatus status, String scope) {
        OffsetDateTime time =
                status == PostStatus.SCHEDULED ? post.getScheduledAt() : post.getUpdatedAt();
        if (time == null) {
            return null;
        }
        return CursorCodec.encode(new Cursor(TimeCursors.toMicros(time), post.getId()), scope);
    }
}
