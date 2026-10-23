package com.app.modules.post.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.dto.response.SavedPostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostSave;
import com.app.modules.post.entity.PostSaveId;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostSaveRepository;
import com.app.modules.post.service.PostSaveService;
import com.app.modules.post.service.PostVisibilityService;

@Service
public class PostSaveServiceImpl implements PostSaveService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PostRepository postRepository;
    private final PostSaveRepository postSaveRepository;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;
    private final OutboxService outboxService;

    public PostSaveServiceImpl(
            PostRepository postRepository,
            PostSaveRepository postSaveRepository,
            PostVisibilityService postVisibilityService,
            PostResponseAssembler postResponseAssembler,
            OutboxService outboxService) {
        this.postRepository = postRepository;
        this.postSaveRepository = postSaveRepository;
        this.postVisibilityService = postVisibilityService;
        this.postResponseAssembler = postResponseAssembler;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public void savePost(UUID userId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        // Only a published post can be saved, by anyone including its owner; anything else
        // surfaces as not-found.
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        if (!postVisibilityService.isVisibleTo(userId, post)) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        PostSaveId saveId = new PostSaveId(userId, postId);
        if (postSaveRepository.existsById(saveId)) {
            throw new AppException(ApiErrorCode.POST_ALREADY_SAVED);
        }
        try {
            postSaveRepository.saveAndFlush(PostSave.builder().id(saveId).build());
        } catch (DataIntegrityViolationException ex) {
            // A concurrent double-submit lost the insert race; the (user_id, post_id) primary key
            // already recorded the save, so surface the same clean conflict rather than a 500.
            throw new AppException(ApiErrorCode.POST_ALREADY_SAVED);
        }
        outboxService.enqueue(
                PostEventTypes.POST_SAVED_V1,
                PostEventTypes.POST_SAVED_V1,
                "post",
                postId,
                userId,
                Map.of(
                        "postId", postId.toString(),
                        "postOwnerId", post.getUserId().toString(),
                        "userId", userId.toString()));
    }

    @Override
    @Transactional
    public void unsavePost(UUID userId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        // Unpublished posts surface as not-found to avoid leaking their existence, and a missing
        // save row collapses onto the same code so it cannot serve as a separate oracle.
        if (post.getStatus() != PostStatus.PUBLISHED && !userId.equals(post.getUserId())) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        // A published post can still be hidden from this viewer by a block, a private owner
        // without an accepted follow, or a soft-deleted owner; apply the same account-level
        // decision savePost uses before mutating the relation.
        if (!postVisibilityService.isVisibleTo(userId, post)) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        // Conditional delete rather than load-then-delete(entity): the latter raises
        // ObjectOptimisticLockingFailureException (-> 500) when a concurrent duplicate request
        // already removed the same row. A missing save row still collapses onto POST_NOT_FOUND,
        // matching the visibility checks above, so it cannot serve as a separate "have you saved
        // this" oracle.
        int deleted = postSaveRepository.deleteByUserAndPost(userId, postId);
        if (deleted == 0) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<SavedPostResponse> listSavedPosts(
            UUID userId, String cursor, int size) {
        int pageSize = normalizeLimit(size);
        Cursor decoded = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<PostSave> saves =
                decoded == null
                        ? postSaveRepository.findFirstSaves(userId, page)
                        : postSaveRepository.findSavesBefore(
                                userId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        boolean hasNextPage = saves.size() > pageSize;
        if (hasNextPage) {
            saves = saves.subList(0, pageSize);
        }
        if (saves.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }
        List<UUID> postIds = saves.stream().map(s -> s.getId().getPostId()).toList();
        // findAllById drops soft-deleted posts via the entity's @SQLRestriction filter.
        Map<UUID, Post> posts =
                postRepository.findAllById(postIds).stream()
                        .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                        .filter(p -> postVisibilityService.isVisibleTo(userId, p))
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        // Post-fetch filtering can shrink a page below the requested size; cursors stay correct
        // because they encode post_saves.created_at, not row counts.
        List<PostSave> visibleSaves =
                saves.stream().filter(s -> posts.containsKey(s.getId().getPostId())).toList();
        List<Post> orderedPosts =
                visibleSaves.stream().map(s -> posts.get(s.getId().getPostId())).toList();
        List<PostResponse> responses = postResponseAssembler.assemble(userId, orderedPosts);
        List<SavedPostResponse> content = new ArrayList<>(visibleSaves.size());
        for (int i = 0; i < visibleSaves.size(); i++) {
            content.add(
                    new SavedPostResponse(responses.get(i), visibleSaves.get(i).getCreatedAt()));
        }
        PostSave firstSave = saves.get(0);
        PostSave lastSave = saves.get(saves.size() - 1);
        String startCursor = encodeCursor(firstSave.getCreatedAt(), firstSave.getId().getPostId());
        String endCursor = encodeCursor(lastSave.getCreatedAt(), lastSave.getId().getPostId());
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    private int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }

    private String encodeCursor(OffsetDateTime time, UUID tiebreaker) {
        if (time == null || tiebreaker == null) {
            return null;
        }
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(time), tiebreaker), CursorScope.POST_SAVES);
    }

    private Cursor decodeCursor(String cursor) {
        return CursorCodec.decode(cursor, CursorScope.POST_SAVES);
    }
}
