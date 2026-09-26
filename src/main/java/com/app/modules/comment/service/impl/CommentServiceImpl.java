package com.app.modules.comment.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.app.modules.comment.config.CommentProperties;
import com.app.modules.comment.dto.request.CreateCommentRequest;
import com.app.modules.comment.dto.request.EditCommentRequest;
import com.app.modules.comment.dto.response.CommentContextResponse;
import com.app.modules.comment.dto.response.CommentDeletionScopeResponse;
import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.entity.Comment;
import com.app.modules.comment.entity.CommentLike;
import com.app.modules.comment.entity.CommentLikeId;
import com.app.modules.comment.entity.CommentWriteIdempotency;
import com.app.modules.comment.enums.CommentSortOrder;
import com.app.modules.comment.mapper.CommentMapper;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.comment.observability.CommentMetrics;
import com.app.modules.comment.repository.CommentIdempotencyRepository;
import com.app.modules.comment.repository.CommentLikeRepository;
import com.app.modules.comment.repository.CommentRepository;
import com.app.modules.comment.repository.CommentUserRepository;
import com.app.modules.comment.service.CommentAccessPolicyService;
import com.app.modules.comment.service.CommentModerationService;
import com.app.modules.comment.service.CommentService;
import com.app.modules.comment.service.CommentViewerStateService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.service.UserSummaryService;

import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Pattern MENTION = Pattern.compile("@([a-zA-Z0-9_]{1,30})");
    private static final int MAX_MENTIONS = 10;
    private static final int MAX_DEPTH = 10;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int PINNED_COMMENT_COUNT = 3;
    private static final String AGGREGATE_TYPE = "comment";

    private final CommentRepository commentRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentIdempotencyRepository idempotencyRepository;
    private final PostRepository postRepository;
    private final CommentUserRepository commentUserRepository;
    private final CommentModerationService moderationService;
    private final CommentAccessPolicyService accessPolicy;
    private final CommentMapper mapper;
    private final OutboxService outboxService;
    private final CommentProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CommentMetrics metrics;
    private final PostVisibilityService postVisibilityService;
    private final UserSummaryService userSummaryService;
    private final CommentViewerStateService commentViewerStateService;
    private final BlockRepository blockRepository;

    public CommentServiceImpl(
            CommentRepository commentRepository,
            CommentLikeRepository commentLikeRepository,
            CommentIdempotencyRepository idempotencyRepository,
            PostRepository postRepository,
            CommentUserRepository commentUserRepository,
            CommentModerationService moderationService,
            CommentAccessPolicyService accessPolicy,
            CommentMapper mapper,
            OutboxService outboxService,
            CommentProperties properties,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CommentMetrics metrics,
            PostVisibilityService postVisibilityService,
            UserSummaryService userSummaryService,
            CommentViewerStateService commentViewerStateService,
            BlockRepository blockRepository) {
        this.commentRepository = commentRepository;
        this.commentLikeRepository = commentLikeRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.postRepository = postRepository;
        this.commentUserRepository = commentUserRepository;
        this.moderationService = moderationService;
        this.accessPolicy = accessPolicy;
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.postVisibilityService = postVisibilityService;
        this.userSummaryService = userSummaryService;
        this.commentViewerStateService = commentViewerStateService;
        this.blockRepository = blockRepository;
    }

    @Override
    @Transactional
    public CommentResponse createComment(
            UUID actorId, CreateCommentRequest request, String idempotencyKey) {
        Timer.Sample sample = Timer.start();
        MDC.put("postId", String.valueOf(request.postId()));
        MDC.put("userId", String.valueOf(actorId));
        try {
            return doCreateComment(actorId, request, idempotencyKey);
        } finally {
            sample.stop(metrics.createLatency());
            clearWriteMdc();
        }
    }

    private CommentResponse doCreateComment(
            UUID actorId, CreateCommentRequest request, String idempotencyKey) {
        Post post =
                postRepository
                        .findById(request.postId())
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
        accessPolicy.assertCanComment(actorId, post);

        short depth = 0;
        UUID rootId = null;
        UUID parentOwnerId = null;
        if (request.parentId() != null) {
            Comment parent =
                    commentRepository
                            .findByIdAndDeletedAtIsNull(request.parentId())
                            .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
            // The parent must belong to the same post the reply targets. Otherwise a reply could
            // attach to a comment on a different post - including one the caller cannot access -
            // bypassing the post-level permission gate and corrupting reply/comment counters.
            if (!parent.getPostId().equals(post.getId())) {
                throw new AppException(ApiErrorCode.COMMENT_NOT_FOUND);
            }
            depth = (short) (parent.getDepth() + 1);
            if (depth > MAX_DEPTH) {
                throw new AppException(ApiErrorCode.COMMENT_DEPTH_EXCEEDED);
            }
            rootId = parent.getRootId() != null ? parent.getRootId() : parent.getId();
            parentOwnerId = parent.getUserId();
        }

        String content =
                com.app.modules.comment.util.CommentContentNormalizer.normalize(request.content());
        CommentModerationService.ModerationResult moderation = moderationService.check(content);
        if (moderation.rejected()) {
            metrics.moderationRejected(moderation.reason());
            throw new AppException(ApiErrorCode.COMMENT_MODERATION_REJECTED);
        }

        // Reserve the idempotency key in the same transaction via ON CONFLICT DO NOTHING. A
        // duplicate key returns a clean replay/conflict instead of poisoning the transaction with a
        // constraint violation, and a rolled-back create frees the key.
        String requestHash = sha256(post.getId() + "|" + request.parentId() + "|" + content);
        if (idempotencyKey != null
                && idempotencyRepository.insertIfAbsent(actorId, idempotencyKey, requestHash)
                        == 0) {
            return replayOrConflict(actorId, idempotencyKey, requestHash);
        }

        enforceSlowMode(post.getId(), actorId);

        // Flush forces the INSERT (and its @CreationTimestamp/@UpdateTimestamp assignment) before
        // the response is assembled below; save() alone leaves both timestamps null until the
        // persistence context eventually flushes on its own schedule.
        Comment saved =
                commentRepository.saveAndFlush(
                        Comment.builder()
                                .postId(post.getId())
                                .userId(actorId)
                                .parentId(request.parentId())
                                .rootId(rootId)
                                .depth(depth)
                                .content(content)
                                .moderationStatus("approved")
                                .build());
        MDC.put("commentId", saved.getId().toString());

        // hasReported is false without a lookup: the actor is the author of the comment just
        // created, and self-reporting is rejected, so no report by them against it can exist.
        CommentResponse response =
                mapper.toResponse(
                        saved,
                        loadAuthor(saved.getUserId()),
                        loadIsLiked(actorId, saved.getId()),
                        false);

        if (idempotencyKey != null) {
            idempotencyRepository.updateResponseBody(
                    actorId, idempotencyKey, objectMapper.writeValueAsString(response));
        }

        List<String> mentionedUserIds = resolveMentions(content, actorId);
        enqueueCreated(post, saved, actorId, parentOwnerId, mentionedUserIds, response);
        return response;
    }

    @Override
    @Transactional
    public CommentResponse editComment(UUID actorId, UUID commentId, EditCommentRequest request) {
        putWriteMdc(commentId, actorId);
        try {
            Comment comment =
                    commentRepository
                            .findByIdAndDeletedAtIsNull(commentId)
                            .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
            if (!comment.getUserId().equals(actorId)) {
                throw new AppException(ApiErrorCode.COMMENT_FORBIDDEN);
            }
            MDC.put("postId", comment.getPostId().toString());
            String content =
                    com.app.modules.comment.util.CommentContentNormalizer.normalize(
                            request.content());
            CommentModerationService.ModerationResult moderation = moderationService.check(content);
            if (moderation.rejected()) {
                metrics.moderationRejected(moderation.reason());
                throw new AppException(ApiErrorCode.COMMENT_MODERATION_REJECTED);
            }
            comment.setContent(content);
            // The only place edited_at is written. Truncated to the microsecond resolution
            // TIMESTAMPTZ stores, so the value returned here is byte-identical to the one a
            // subsequent read returns rather than carrying JVM nanoseconds the column drops.
            comment.setEditedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
            // Flush forces the UPDATE (and its trg_comments_updated_at trigger) before the
            // response and the broadcast projection are assembled from the entity below. Under
            // save() alone the flush happened after this method had already read updated_at, so
            // both carried the value the previous edit left behind, one edit behind the row and
            // behind the edited_at set just above it.
            Comment saved = commentRepository.saveAndFlush(comment);
            // hasReported is false without a lookup: only the author may edit, and self-reporting
            // is rejected, so no report by them against this comment can exist.
            CommentResponse response =
                    mapper.toResponse(
                            saved,
                            loadAuthor(saved.getUserId()),
                            loadIsLiked(actorId, saved.getId()),
                            false);

            Map<String, Object> data = new HashMap<>();
            data.put("postId", saved.getPostId().toString());
            data.put("commentId", saved.getId().toString());
            data.put("commentOwnerId", saved.getUserId().toString());
            data.put("depth", (int) saved.getDepth());
            // Broadcast projection: this payload is fanned out to every subscriber of the post's
            // live stream, so the editor's own isLiked state must not ride along as if it applied
            // to every viewer.
            data.put("comment", mapper.toBroadcastResponse(response));
            outboxService.enqueue(
                    CommentEventTypes.COMMENT_EDITED_V1,
                    CommentEventTypes.COMMENT_EDITED_V1,
                    AGGREGATE_TYPE,
                    saved.getId(),
                    actorId,
                    data);
            return response;
        } finally {
            clearWriteMdc();
        }
    }

    @Override
    @Transactional
    public CommentDeletionScopeResponse deleteComment(UUID actorId, UUID commentId) {
        putWriteMdc(commentId, actorId);
        try {
            Comment comment =
                    commentRepository
                            .findByIdAndDeletedAtIsNull(commentId)
                            .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
            if (!comment.getUserId().equals(actorId) && !isCurrentUserAdmin()) {
                throw new AppException(ApiErrorCode.COMMENT_FORBIDDEN);
            }
            MDC.put("postId", comment.getPostId().toString());
            // The statement's own affected-row count is the authoritative figure for how many
            // comments this delete removed; no second query can match it without a race.
            int deletedCommentCount =
                    commentRepository.softDeleteSubtree(commentId, OffsetDateTime.now());

            Map<String, Object> data = new HashMap<>();
            data.put("postId", comment.getPostId().toString());
            data.put("commentId", comment.getId().toString());
            // The comment's own owner, not actorId - a moderator can delete another user's
            // comment, and the fan-out filter must key off who authored the content, not who
            // performed this action.
            data.put("commentOwnerId", comment.getUserId().toString());
            // A single delete event stands for a whole subtree, so a live subscriber that removed
            // only the named comment would leave its descendants rendered as orphans.
            data.put("deletedCommentCount", deletedCommentCount);
            if (comment.getRootId() != null) {
                data.put("rootId", comment.getRootId().toString());
            }
            outboxService.enqueue(
                    CommentEventTypes.COMMENT_DELETED_V1,
                    CommentEventTypes.COMMENT_DELETED_V1,
                    AGGREGATE_TYPE,
                    comment.getId(),
                    actorId,
                    data);
            return new CommentDeletionScopeResponse(deletedCommentCount);
        } finally {
            clearWriteMdc();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CommentDeletionScopeResponse getDeletionScope(UUID actorId, UUID commentId) {
        Comment comment =
                commentRepository
                        .findByIdAndDeletedAtIsNull(commentId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
        // The delete this previews answers a non-owner with 403, which confirms the comment
        // exists. A read-only preview has no reason to concede that, so every caller without the
        // authority to delete gets the same answer as one asking about a comment that is not
        // there.
        // Deliberately no post-visibility gate: the delete has none either, so a user whose comment
        // sits on a post that has since become invisible to them can still delete it, and a
        // preview that refused would block a dialogue for an action that goes on to succeed.
        if (!comment.getUserId().equals(actorId) && !isCurrentUserAdmin()) {
            throw new AppException(ApiErrorCode.COMMENT_NOT_FOUND);
        }
        return new CommentDeletionScopeResponse(commentRepository.countSubtree(commentId));
    }

    @Override
    @Transactional
    public void likeComment(UUID actorId, UUID commentId) {
        putWriteMdc(commentId, actorId);
        try {
            Comment comment =
                    commentRepository
                            .findByIdAndDeletedAtIsNull(commentId)
                            .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
            MDC.put("postId", comment.getPostId().toString());
            Post post =
                    postRepository
                            .findById(comment.getPostId())
                            .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
            assertCanRead(actorId, post);
            if (commentLikeRepository.existsByIdUserIdAndIdCommentId(actorId, commentId)) {
                throw new AppException(ApiErrorCode.COMMENT_ALREADY_LIKED);
            }
            try {
                commentLikeRepository.saveAndFlush(
                        CommentLike.builder().id(new CommentLikeId(actorId, commentId)).build());
            } catch (DataIntegrityViolationException ex) {
                // A concurrent double-submit lost the insert race; the composite (user_id,
                // comment_id) primary key already recorded the like, so surface the same clean
                // conflict rather than a 500.
                throw new AppException(ApiErrorCode.COMMENT_ALREADY_LIKED);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("postId", comment.getPostId().toString());
            data.put("commentId", comment.getId().toString());
            data.put("commentOwnerId", comment.getUserId().toString());
            outboxService.enqueue(
                    CommentEventTypes.COMMENT_LIKED_V1,
                    CommentEventTypes.COMMENT_LIKED_V1,
                    AGGREGATE_TYPE,
                    comment.getId(),
                    actorId,
                    data);
        } finally {
            clearWriteMdc();
        }
    }

    @Override
    @Transactional
    public void unlikeComment(UUID actorId, UUID commentId) {
        putWriteMdc(commentId, actorId);
        try {
            Comment comment =
                    commentRepository
                            .findByIdAndDeletedAtIsNull(commentId)
                            .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
            MDC.put("postId", comment.getPostId().toString());
            Post post =
                    postRepository
                            .findById(comment.getPostId())
                            .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
            assertCanRead(actorId, post);
            int removed = commentLikeRepository.deleteByUserAndComment(actorId, commentId);
            if (removed == 0) {
                throw new AppException(ApiErrorCode.COMMENT_NOT_LIKED);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("postId", comment.getPostId().toString());
            data.put("commentId", comment.getId().toString());
            data.put("commentOwnerId", comment.getUserId().toString());
            outboxService.enqueue(
                    CommentEventTypes.COMMENT_UNLIKED_V1,
                    CommentEventTypes.COMMENT_UNLIKED_V1,
                    AGGREGATE_TYPE,
                    comment.getId(),
                    actorId,
                    data);
        } finally {
            clearWriteMdc();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<CommentResponse> listTopLevelComments(
            UUID viewerId, UUID postId, String sort, String cursor, int limit) {
        Post post =
                postRepository
                        .findById(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        assertCanRead(viewerId, post);
        CommentSortOrder order = CommentSortOrder.from(sort);
        String scope = order.cursorScope();
        int pageSize = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor, scope);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        // Resolved on every page, not only the first. The block itself is prepended to page one
        // alone, but its ids must be excluded from the chronological body of every page: a pinned
        // comment old enough to fall on a later page would otherwise be returned twice, once at
        // the head of page one and again in its own chronological position.
        //
        // In the newest mode the block is not computed at all and the exclusion array is empty,
        // so the same two queries serve both modes and neither carries a mode-specific clause.
        List<Comment> pinned =
                order.pinsTopComments()
                        ? commentRepository.findTopLikedTopLevel(
                                postId, viewerId, PageRequest.of(0, PINNED_COMMENT_COUNT))
                        : List.of();
        UUID[] pinnedIds = pinned.stream().map(Comment::getId).toArray(UUID[]::new);
        if (decoded != null) {
            List<Comment> comments =
                    commentRepository.findTopLevelBefore(
                            postId,
                            pinnedIds,
                            viewerId,
                            TimeCursors.fromMicros(decoded.sortValueMicros()),
                            decoded.id(),
                            page);
            return toPage(viewerId, comments, pageSize, cursor, scope);
        }
        List<Comment> comments =
                commentRepository.findFirstTopLevelExcluding(postId, pinnedIds, viewerId, page);
        return toPage(viewerId, pinned, comments, pageSize, cursor, scope);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<CommentResponse> listReplies(
            UUID viewerId, UUID commentId, String cursor, int limit) {
        Comment parent =
                commentRepository
                        .findByIdAndDeletedAtIsNull(commentId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
        Post post =
                postRepository
                        .findById(parent.getPostId())
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        assertCanRead(viewerId, post);
        int pageSize = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor, CursorScope.COMMENT_REPLIES);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Comment> replies =
                decoded == null
                        ? commentRepository.findFirstReplies(commentId, viewerId, page)
                        : commentRepository.findRepliesBefore(
                                commentId,
                                viewerId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                page);
        return toPage(viewerId, replies, pageSize, cursor, CursorScope.COMMENT_REPLIES);
    }

    @Override
    @Transactional(readOnly = true)
    public CommentContextResponse getContext(UUID viewerId, UUID commentId) {
        List<Comment> chain = commentRepository.findAncestryIncludingHidden(commentId);
        boolean reachable =
                !chain.isEmpty()
                        && chain.get(chain.size() - 1).getId().equals(commentId)
                        && chain.get(0).getParentId() == null
                        && chain.stream().allMatch(c -> isListable(viewerId, c));
        if (!reachable) {
            throw new AppException(ApiErrorCode.COMMENT_NOT_FOUND);
        }
        UUID postId = chain.get(0).getPostId();
        Post post =
                postRepository
                        .findById(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.COMMENT_NOT_FOUND));
        if (viewerId == null || !postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.COMMENT_NOT_FOUND);
        }
        Map<UUID, UserSummaryResponse> authors =
                userSummaryService.loadSummaries(chain.stream().map(Comment::getUserId).toList());
        List<UUID> ids = chain.stream().map(Comment::getId).toList();
        Set<UUID> liked = commentViewerStateService.loadLikedCommentIds(viewerId, ids);
        Set<UUID> reported = commentViewerStateService.loadReportedCommentIds(viewerId, ids);
        return new CommentContextResponse(
                postId, chain.stream().map(c -> toResponse(c, authors, liked, reported)).toList());
    }

    // The same predicate the reply and top-level list queries apply, so the context endpoint shows
    // exactly what paging through the lists would have shown.
    private boolean isListable(UUID viewerId, Comment comment) {
        return comment.getDeletedAt() == null
                && comment.getAdminRemovedAt() == null
                && "approved".equals(comment.getModerationStatus())
                && (viewerId == null
                        || !blockRepository.existsBetween(viewerId, comment.getUserId()));
    }

    // Visibility gate shared by the read, like, and unlike paths, consistent with the create path
    // and the WebSocket SUBSCRIBE interceptor. A null viewer (anonymous) cannot read; post
    // visibility covers block and private-follow rules.
    private void assertCanRead(UUID viewerId, Post post) {
        if (viewerId == null || !postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.POST_FORBIDDEN);
        }
    }

    private CursorPageResponse<CommentResponse> toPage(
            UUID viewerId, List<Comment> rows, int pageSize, String cursor, String scope) {
        return toPage(viewerId, List.of(), rows, pageSize, cursor, scope);
    }

    // Keyset pagination over limit+1 rows: hasNextPage is decided by the pre-trim size, then the
    // extra probe row is dropped. The pinned block is prepended and is additional to pageSize, so
    // the body remains a full keyset page and the cursor advances by exactly pageSize.
    private CursorPageResponse<CommentResponse> toPage(
            UUID viewerId,
            List<Comment> pinned,
            List<Comment> rows,
            int pageSize,
            String cursor,
            String scope) {
        boolean hasNextPage = rows.size() > pageSize;
        List<Comment> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        List<Comment> all = new ArrayList<>(pinned.size() + page.size());
        all.addAll(pinned);
        all.addAll(page);
        // Every batch loader is called once over the pinned block and the body together, so the
        // pinned block adds no query and the query count stays flat in page size.
        Map<UUID, UserSummaryResponse> authors =
                userSummaryService.loadSummaries(all.stream().map(Comment::getUserId).toList());
        List<UUID> allIds = all.stream().map(Comment::getId).toList();
        Set<UUID> likedCommentIds = commentViewerStateService.loadLikedCommentIds(viewerId, allIds);
        Set<UUID> reportedCommentIds =
                commentViewerStateService.loadReportedCommentIds(viewerId, allIds);
        List<CommentResponse> content = new ArrayList<>(all.size());
        pinned.forEach(
                c ->
                        content.add(
                                toResponse(c, authors, likedCommentIds, reportedCommentIds)
                                        .asPinned()));
        page.forEach(c -> content.add(toResponse(c, authors, likedCommentIds, reportedCommentIds)));
        // Cursors describe the newest-first body only. Deriving them from the pinned block would
        // seek the next page to an arbitrary position in the stream.
        Comment first = page.isEmpty() ? null : page.get(0);
        Comment last = page.isEmpty() ? null : page.get(page.size() - 1);
        String startCursor =
                first == null ? null : encodeCursor(first.getCreatedAt(), first.getId(), scope);
        String endCursor =
                last == null ? null : encodeCursor(last.getCreatedAt(), last.getId(), scope);
        return CursorPageResponse.<CommentResponse>builder()
                .content(content)
                .pageInfo(
                        CursorPageResponse.PageInfo.builder()
                                .hasNextPage(hasNextPage)
                                .hasPreviousPage(cursor != null)
                                .startCursor(startCursor)
                                .endCursor(endCursor)
                                .build())
                .build();
    }

    private CommentResponse toResponse(
            Comment comment,
            Map<UUID, UserSummaryResponse> authors,
            Set<UUID> likedCommentIds,
            Set<UUID> reportedCommentIds) {
        return mapper.toResponse(
                comment,
                authors.get(comment.getUserId()),
                likedCommentIds.contains(comment.getId()),
                reportedCommentIds.contains(comment.getId()));
    }

    // Resolves a single author's public summary; the create and edit paths return exactly one
    // comment, so the batch loader is called with a singleton id.
    private UserSummaryResponse loadAuthor(UUID userId) {
        return userSummaryService.loadSummaries(List.of(userId)).get(userId);
    }

    // Resolves a single comment's viewer-like state; the create and edit paths return exactly one
    // comment, so the batch loader is called with a singleton id.
    private boolean loadIsLiked(UUID viewerId, UUID commentId) {
        return commentViewerStateService
                .loadLikedCommentIds(viewerId, List.of(commentId))
                .contains(commentId);
    }

    // Re-reads the existing idempotency row to replay the cached response, or to reject a key
    // reused with a different payload. A null body means a concurrent in-flight create reserved the
    // key but has not committed its response yet, which is treated as a conflict.
    private CommentResponse replayOrConflict(
            UUID actorId, String idempotencyKey, String requestHash) {
        CommentWriteIdempotency row =
                idempotencyRepository
                        .findByUserIdAndIdempotencyKey(actorId, idempotencyKey)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.COMMENT_IDEMPOTENCY_CONFLICT));
        if (!requestHash.equals(row.getRequestHash()) || row.getResponseBody() == null) {
            throw new AppException(ApiErrorCode.COMMENT_IDEMPOTENCY_CONFLICT);
        }
        metrics.idempotencyReplay();
        try {
            return objectMapper.readValue(row.getResponseBody(), CommentResponse.class);
        } catch (Exception e) {
            throw new AppException(ApiErrorCode.INTERNAL_ERROR, "Failed to read cached response");
        }
    }

    // Pre-persist SETNX: a rolled-back create can briefly hold the slow-mode key, which is
    // acceptable at the small TTLs slow mode uses.
    private void enforceSlowMode(UUID postId, UUID actorId) {
        int seconds = properties.slowModeSeconds();
        if (seconds <= 0) {
            return;
        }
        String key = "comment:slowmode:" + postId + ":" + actorId;
        Boolean acquired =
                redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(seconds));
        if (Boolean.FALSE.equals(acquired)) {
            throw new AppException(ApiErrorCode.COMMENT_SLOW_MODE_ACTIVE);
        }
    }

    private void enqueueCreated(
            Post post,
            Comment saved,
            UUID actorId,
            UUID parentOwnerId,
            List<String> mentionedUserIds,
            CommentResponse response) {
        // The outbox stores data via Map.copyOf, which rejects null values, so null-valued keys are
        // omitted rather than inserted.
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("postOwnerId", post.getUserId().toString());
        data.put("commentId", saved.getId().toString());
        data.put("userId", actorId.toString());
        data.put("commentOwnerId", actorId.toString());
        data.put("depth", (int) saved.getDepth());
        data.put("mentionedUserIds", mentionedUserIds);
        // Broadcast projection: this payload is fanned out to every subscriber of the post's live
        // stream, so the creating actor's own isLiked state must not ride along as if it applied to
        // every viewer.
        data.put("comment", mapper.toBroadcastResponse(response));
        if (saved.getParentId() != null) {
            data.put("parentId", saved.getParentId().toString());
        }
        if (parentOwnerId != null) {
            data.put("parentOwnerId", parentOwnerId.toString());
        }
        if (saved.getRootId() != null) {
            data.put("rootId", saved.getRootId().toString());
        }
        outboxService.enqueue(
                CommentEventTypes.COMMENT_CREATED_V1,
                CommentEventTypes.COMMENT_CREATED_V1,
                AGGREGATE_TYPE,
                saved.getId(),
                actorId,
                data);
    }

    // A mention across a block relationship, in either direction, resolves to nothing: the
    // mentioned user must not receive a notification (the harassment channel the stealth block
    // model exists to close), and the mentioner must not be able to tell resolution was
    // suppressed rather than the username simply not existing, since that distinction is itself a
    // disclosure. The filter applies here, at the single source every downstream consumer of
    // mentionedUserIds reads from - the comment-notification consumer and the live WS fan-out both
    // inherit it for free rather than needing their own copy of this check.
    private List<String> resolveMentions(String content, UUID actorId) {
        Set<String> usernames = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(content);
        while (matcher.find() && usernames.size() < MAX_MENTIONS) {
            usernames.add(matcher.group(1));
        }
        List<String> ids = new java.util.ArrayList<>();
        for (String username : usernames) {
            commentUserRepository
                    .findByUsernameAndDeletedAtIsNull(username)
                    .map(u -> u.getId())
                    .filter(id -> !id.equals(actorId))
                    .filter(id -> !blockRepository.existsBetween(actorId, id))
                    .ifPresent(id -> ids.add(id.toString()));
        }
        return ids;
    }

    // MDC keys are not in the current logback console pattern; they exist for correlation in log
    // aggregation. commentId is added by the caller once the row is persisted.
    private static void putWriteMdc(UUID commentId, UUID actorId) {
        MDC.put("commentId", String.valueOf(commentId));
        MDC.put("userId", String.valueOf(actorId));
    }

    private static void clearWriteMdc() {
        MDC.remove("commentId");
        MDC.remove("postId");
        MDC.remove("userId");
    }

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

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
