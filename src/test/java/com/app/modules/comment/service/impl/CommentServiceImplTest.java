package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.security.user.UserPrincipal;
import com.app.modules.comment.config.CommentProperties;
import com.app.modules.comment.dto.request.CreateCommentRequest;
import com.app.modules.comment.dto.request.EditCommentRequest;
import com.app.modules.comment.dto.response.CommentDeletionScopeResponse;
import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.entity.Comment;
import com.app.modules.comment.entity.CommentWriteIdempotency;
import com.app.modules.comment.mapper.CommentMapper;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.comment.observability.CommentMetrics;
import com.app.modules.comment.repository.CommentIdempotencyRepository;
import com.app.modules.comment.repository.CommentLikeRepository;
import com.app.modules.comment.repository.CommentRepository;
import com.app.modules.comment.repository.CommentUserRepository;
import com.app.modules.comment.service.CommentAccessPolicyService;
import com.app.modules.comment.service.CommentModerationService;
import com.app.modules.comment.service.CommentModerationService.ModerationResult;
import com.app.modules.comment.service.CommentViewerStateService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.service.UserSummaryService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    private static final OffsetDateTime EPOCH =
            OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    // A literal cursor in the wire format issued before the pinned block existed. Hardcoded rather
    // than round-tripped through the encoder so a format change cannot silently pass this test.
    private static final String SECOND_PAGE_CURSOR =
            "Y210OjE3NjcyMjU2MDAwMDAwMDA6MTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTEx";

    @Mock private CommentRepository commentRepository;
    @Mock private CommentLikeRepository commentLikeRepository;
    @Mock private CommentIdempotencyRepository idempotencyRepository;
    @Mock private PostRepository postRepository;
    @Mock private CommentUserRepository commentUserRepository;
    @Mock private CommentModerationService moderationService;
    @Mock private CommentAccessPolicyService accessPolicy;
    @Mock private CommentMapper mapper;
    @Mock private OutboxService outboxService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private CommentMetrics metrics;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private CommentViewerStateService commentViewerStateService;
    @Mock private BlockRepository blockRepository;
    @Mock private InteractionPolicyService interactionPolicy;

    private CommentServiceImpl service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = newService(new CommentProperties(0, 24));
        lenient()
                .when(metrics.createLatency())
                .thenReturn(new SimpleMeterRegistry().timer("comment.create.latency"));
        lenient()
                .when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(new java.util.HashMap<>());
        lenient()
                .when(commentViewerStateService.loadLikedCommentIds(any(), anyCollection()))
                .thenReturn(java.util.Set.of());
        lenient()
                .when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    private CommentServiceImpl newService(CommentProperties properties) {
        return new CommentServiceImpl(
                commentRepository,
                commentLikeRepository,
                idempotencyRepository,
                postRepository,
                commentUserRepository,
                moderationService,
                accessPolicy,
                mapper,
                outboxService,
                properties,
                redisTemplate,
                objectMapper,
                metrics,
                postVisibilityService,
                userSummaryService,
                commentViewerStateService,
                blockRepository,
                interactionPolicy);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Post publishedPost() {
        return Post.builder()
                .id(postId)
                .userId(UUID.randomUUID())
                .status(PostStatus.PUBLISHED)
                .build();
    }

    private Post postOwnedBy(UUID ownerId) {
        return Post.builder().id(postId).userId(ownerId).status(PostStatus.PUBLISHED).build();
    }

    private InteractionRequest commentRequestTo(UUID postOwnerId) {
        return new InteractionRequest(
                actorId, postOwnerId, InteractionType.COMMENT, "hello world", List.of(), null);
    }

    private InteractionRequest mentionRequestTo(UUID mentionedId) {
        return new InteractionRequest(
                actorId, mentionedId, InteractionType.MENTION, null, List.of(), null);
    }

    private Comment approvedComment(UUID authorId) {
        return Comment.builder()
                .id(commentId)
                .postId(postId)
                .userId(authorId)
                .moderationStatus("approved")
                .build();
    }

    private CommentResponse stubSave() {
        Comment saved =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .depth((short) 0)
                        .build();
        CommentResponse mapped = sampleResponse();
        when(commentRepository.saveAndFlush(any())).thenReturn(saved);
        when(mapper.toResponse(eq(saved), any(), anyBoolean(), anyBoolean())).thenReturn(mapped);
        return mapped;
    }

    private void stubUser(String username, UUID userId) {
        when(commentUserRepository.findByUsernameAndDeletedAtIsNull(username))
                .thenReturn(
                        Optional.of(
                                com.app.modules.users.entity.User.builder()
                                        .id(userId)
                                        .username(username)
                                        .build()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createdEventData() {
        ArgumentCaptor<Map<String, Object>> data =
                (ArgumentCaptor<Map<String, Object>>)
                        (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_CREATED_V1),
                        eq(CommentEventTypes.COMMENT_CREATED_V1),
                        eq("comment"),
                        eq(commentId),
                        eq(actorId),
                        data.capture());
        return data.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> editedEventData() {
        ArgumentCaptor<Map<String, Object>> data =
                (ArgumentCaptor<Map<String, Object>>)
                        (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_EDITED_V1),
                        eq(CommentEventTypes.COMMENT_EDITED_V1),
                        eq("comment"),
                        eq(commentId),
                        eq(actorId),
                        data.capture());
        return data.getValue();
    }

    private CreateCommentRequest createRequest(UUID parentId) {
        return new CreateCommentRequest(postId, parentId, "hello world");
    }

    @Test
    void createComment_postNotFound_throwsNotFound() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void createComment_postNotPublished_throwsForbidden() {
        Post draft =
                Post.builder()
                        .id(postId)
                        .userId(UUID.randomUUID())
                        .status(PostStatus.DRAFT)
                        .build();
        when(postRepository.findById(postId)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void createComment_accessDenied_throwsRestricted() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        org.mockito.Mockito.doThrow(new AppException(ApiErrorCode.POST_COMMENTING_RESTRICTED))
                .when(accessPolicy)
                .assertCanComment(eq(actorId), any());
        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_COMMENTING_RESTRICTED);
    }

    @Test
    void createComment_moderationRejected_throwsModerationRejected() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.rejected("spam"));
        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_MODERATION_REJECTED);
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void createComment_depthExceeded_throwsDepthExceeded() {
        UUID parentId = UUID.randomUUID();
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        Comment parent =
                Comment.builder()
                        .id(parentId)
                        .postId(postId)
                        .depth((short) 10)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(parentId))
                .thenReturn(Optional.of(parent));
        assertThatThrownBy(() -> service.createComment(actorId, createRequest(parentId), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_DEPTH_EXCEEDED);
    }

    @Test
    void createComment_success_persistsAndEnqueuesOutbox() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        Comment saved =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .depth((short) 0)
                        .build();
        when(commentRepository.saveAndFlush(any())).thenReturn(saved);
        when(mapper.toResponse(eq(saved), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        CommentResponse response = service.createComment(actorId, createRequest(null), null);

        assertThat(response).isNotNull();
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_CREATED_V1),
                        eq(CommentEventTypes.COMMENT_CREATED_V1),
                        eq("comment"),
                        eq(commentId),
                        eq(actorId),
                        anyMap());
    }

    @Test
    void createComment_mentionsBlockedUser_silentlyExcludesFromMentionedUserIds() {
        UUID unrelatedUserId = UUID.randomUUID();
        UUID blockedUserId = UUID.randomUUID();
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        Comment saved =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .depth((short) 0)
                        .build();
        when(commentRepository.saveAndFlush(any())).thenReturn(saved);
        when(mapper.toResponse(eq(saved), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());
        when(commentUserRepository.findByUsernameAndDeletedAtIsNull("unrelated"))
                .thenReturn(
                        Optional.of(
                                com.app.modules.users.entity.User.builder()
                                        .id(unrelatedUserId)
                                        .username("unrelated")
                                        .build()));
        when(commentUserRepository.findByUsernameAndDeletedAtIsNull("blocked"))
                .thenReturn(
                        Optional.of(
                                com.app.modules.users.entity.User.builder()
                                        .id(blockedUserId)
                                        .username("blocked")
                                        .build()));
        when(blockRepository.existsBetween(actorId, unrelatedUserId)).thenReturn(false);
        when(blockRepository.existsBetween(actorId, blockedUserId)).thenReturn(true);
        CreateCommentRequest request =
                new CreateCommentRequest(postId, null, "hi @unrelated and @blocked");

        service.createComment(actorId, request, null);

        ArgumentCaptor<java.util.Map> dataCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_CREATED_V1),
                        eq(CommentEventTypes.COMMENT_CREATED_V1),
                        eq("comment"),
                        eq(commentId),
                        eq(actorId),
                        dataCaptor.capture());
        @SuppressWarnings("unchecked")
        List<String> mentionedUserIds =
                (List<String>) dataCaptor.getValue().get("mentionedUserIds");
        assertThat(mentionedUserIds)
                .containsExactly(unrelatedUserId.toString())
                .doesNotContain(blockedUserId.toString());
    }

    @Test
    void createComment_idempotencyReplay_returnsCachedResponse() {
        Post post = publishedPost();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        String hash = sha256(post.getId() + "|null|hello world");
        when(idempotencyRepository.insertIfAbsent(eq(actorId), eq("key-1"), eq(hash)))
                .thenReturn(0);
        CommentWriteIdempotency row =
                CommentWriteIdempotency.builder()
                        .userId(actorId)
                        .idempotencyKey("key-1")
                        .requestHash(hash)
                        .responseBody("cached-json")
                        .build();
        when(idempotencyRepository.findByUserIdAndIdempotencyKey(actorId, "key-1"))
                .thenReturn(Optional.of(row));
        CommentResponse cached = sampleResponse();
        when(objectMapper.readValue("cached-json", CommentResponse.class)).thenReturn(cached);

        CommentResponse response = service.createComment(actorId, createRequest(null), "key-1");

        assertThat(response).isSameAs(cached);
        verify(commentRepository, never()).save(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void createComment_idempotencyConflict_throwsConflict() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(idempotencyRepository.insertIfAbsent(eq(actorId), eq("key-2"), any())).thenReturn(0);
        CommentWriteIdempotency row =
                CommentWriteIdempotency.builder()
                        .userId(actorId)
                        .idempotencyKey("key-2")
                        .requestHash("a-different-hash")
                        .responseBody("cached-json")
                        .build();
        when(idempotencyRepository.findByUserIdAndIdempotencyKey(actorId, "key-2"))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), "key-2"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_IDEMPOTENCY_CONFLICT);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void editComment_notOwner_throwsForbidden() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        assertThatThrownBy(
                        () ->
                                service.editComment(
                                        actorId, commentId, new EditCommentRequest("new")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_FORBIDDEN);
    }

    @Test
    void editComment_success_enqueuesEditedEvent() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(commentRepository.saveAndFlush(any())).thenReturn(comment);
        when(mapper.toResponse(eq(comment), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        service.editComment(actorId, commentId, new EditCommentRequest("updated"));

        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_EDITED_V1),
                        eq(CommentEventTypes.COMMENT_EDITED_V1),
                        eq("comment"),
                        eq(commentId),
                        eq(actorId),
                        anyMap());
    }

    @Test
    void editComment_stampsEditedAtBeforeSaving() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(commentRepository.saveAndFlush(any())).thenReturn(comment);
        when(mapper.toResponse(eq(comment), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        service.editComment(actorId, commentId, new EditCommentRequest("updated"));

        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEditedAt()).isNotNull();
        assertThat(saved.getValue().getContent()).isEqualTo("updated");
    }

    @Test
    void create_policyAllowHidden_persistsHiddenWithReasonAndEnqueuesHiddenVisibility() {
        Post post = publishedPost();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(commentRequestTo(post.getUserId())))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN, InteractionReason.HIDDEN_WORD));
        stubSave();

        service.createComment(actorId, createRequest(null), null);

        ArgumentCaptor<Comment> persisted = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().getModerationStatus()).isEqualTo("hidden");
        assertThat(persisted.getValue().getHiddenReason()).isEqualTo("hidden_words");
        assertThat(createdEventData().get("visibility")).isEqualTo("hidden");
    }

    @Test
    void create_policyAllowHiddenForALimit_persistsLimitedInteractionReason() {
        Post post = publishedPost();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(commentRequestTo(post.getUserId())))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_RECENT_FOLLOWER));
        stubSave();

        service.createComment(actorId, createRequest(null), null);

        ArgumentCaptor<Comment> persisted = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().getHiddenReason()).isEqualTo("limited_interaction");
    }

    @Test
    void create_policyAllow_persistsApprovedWithoutReasonAndEnqueuesVisibleVisibility() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        stubSave();

        service.createComment(actorId, createRequest(null), null);

        ArgumentCaptor<Comment> persisted = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().getModerationStatus()).isEqualTo("approved");
        assertThat(persisted.getValue().getHiddenReason()).isNull();
        assertThat(createdEventData().get("visibility")).isEqualTo("visible");
    }

    @Test
    void create_policyDenyForABlockWithThePostOwner_throwsPostCommentingRestricted() {
        Post post = publishedPost();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(commentRequestTo(post.getUserId())))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));
        when(blockRepository.existsBetween(actorId, post.getUserId())).thenReturn(true);

        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_COMMENTING_RESTRICTED);
        verify(commentRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_policyDenyForABlockWithTheParentAuthor_throwsCommentNotFound() {
        Post post = publishedPost();
        UUID parentId = UUID.randomUUID();
        UUID parentAuthorId = UUID.randomUUID();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(commentRepository.findByIdAndDeletedAtIsNull(parentId))
                .thenReturn(
                        Optional.of(
                                Comment.builder()
                                        .id(parentId)
                                        .postId(postId)
                                        .userId(parentAuthorId)
                                        .depth((short) 0)
                                        .moderationStatus("approved")
                                        .build()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(
                        new InteractionRequest(
                                actorId,
                                post.getUserId(),
                                InteractionType.COMMENT,
                                "hello world",
                                List.of(parentAuthorId),
                                null)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));
        when(blockRepository.existsBetween(actorId, post.getUserId())).thenReturn(false);

        assertThatThrownBy(() -> service.createComment(actorId, createRequest(parentId), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
        verify(commentRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_spamCapsStillRejected422_beforePolicyContentCheck() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.rejected("spam"));

        assertThatThrownBy(() -> service.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_MODERATION_REJECTED);
        verify(interactionPolicy, never()).evaluate(any());
    }

    @Test
    void create_responseToAuthorCarriesNoHiddenMarker() {
        Post post = publishedPost();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(commentRequestTo(post.getUserId())))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN, InteractionReason.HIDDEN_WORD));
        CommentResponse mapped = stubSave();

        CommentResponse response = service.createComment(actorId, createRequest(null), null);

        assertThat(response).isSameAs(mapped);
        assertThat(
                        java.util.Arrays.stream(CommentResponse.class.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName))
                .noneMatch(
                        name -> name.toLowerCase().contains("hidden") || name.contains("status"));
    }

    @Test
    void mentions_limitedTargetExcludedFromMentionedUserIds() {
        UUID openId = UUID.randomUUID();
        UUID limitedId = UUID.randomUUID();
        UUID deniedId = UUID.randomUUID();
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        stubSave();
        stubUser("open", openId);
        stubUser("limited", limitedId);
        stubUser("denied", deniedId);
        when(interactionPolicy.evaluate(mentionRequestTo(limitedId)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_NON_FOLLOWER));
        when(interactionPolicy.evaluate(mentionRequestTo(deniedId)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        service.createComment(
                actorId, new CreateCommentRequest(postId, null, "hi @open @limited @denied"), null);

        @SuppressWarnings("unchecked")
        List<String> mentioned = (List<String>) createdEventData().get("mentionedUserIds");
        assertThat(mentioned).containsExactly(openId.toString());
        verify(interactionPolicy, org.mockito.Mockito.times(1)).evaluate(mentionRequestTo(openId));
        verify(interactionPolicy, org.mockito.Mockito.times(1))
                .evaluate(mentionRequestTo(limitedId));
    }

    @Test
    void mentions_sameAccountNamedTwice_isEvaluatedOnce() {
        UUID openId = UUID.randomUUID();
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        stubSave();
        stubUser("open", openId);
        stubUser("Open", openId);

        service.createComment(
                actorId, new CreateCommentRequest(postId, null, "hi @open and @Open"), null);

        @SuppressWarnings("unchecked")
        List<String> mentioned = (List<String>) createdEventData().get("mentionedUserIds");
        assertThat(mentioned).containsExactly(openId.toString());
        verify(interactionPolicy, org.mockito.Mockito.times(1)).evaluate(mentionRequestTo(openId));
    }

    @Test
    void edit_approvedCommentGainingHiddenWord_becomesHidden() {
        Post post = publishedPost();
        Comment comment = approvedComment(actorId);
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(
                        new InteractionRequest(
                                actorId,
                                post.getUserId(),
                                InteractionType.COMMENT,
                                "updated",
                                List.of(),
                                null)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN, InteractionReason.HIDDEN_WORD));
        when(commentRepository.saveAndFlush(any())).thenReturn(comment);
        when(mapper.toResponse(eq(comment), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        service.editComment(actorId, commentId, new EditCommentRequest("updated"));

        assertThat(comment.getModerationStatus()).isEqualTo("hidden");
        assertThat(comment.getHiddenReason()).isEqualTo("hidden_words");
    }

    @Test
    void edit_approvedCommentAllowedByThePolicy_staysApproved() {
        Comment comment = approvedComment(actorId);
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(commentRepository.saveAndFlush(any())).thenReturn(comment);
        when(mapper.toResponse(eq(comment), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        service.editComment(actorId, commentId, new EditCommentRequest("updated"));

        assertThat(comment.getModerationStatus()).isEqualTo("approved");
        assertThat(comment.getHiddenReason()).isNull();
    }

    @Test
    void edit_hiddenCommentLosingHiddenWord_staysHidden() {
        Comment comment = approvedComment(actorId);
        comment.setModerationStatus("hidden");
        comment.setHiddenReason("hidden_words");
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(commentRepository.saveAndFlush(any())).thenReturn(comment);
        when(mapper.toResponse(eq(comment), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        service.editComment(actorId, commentId, new EditCommentRequest("now polite"));

        assertThat(comment.getModerationStatus()).isEqualTo("hidden");
        assertThat(comment.getHiddenReason()).isEqualTo("hidden_words");
        assertThat(editedEventData().get("visibility")).isEqualTo("hidden");
    }

    @Test
    void edit_hiddenCommentGainingAnotherHiddenReason_keepsItsOriginalReason() {
        Comment comment = approvedComment(actorId);
        comment.setModerationStatus("hidden");
        comment.setHiddenReason("limited_interaction");
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN, InteractionReason.HIDDEN_WORD));
        when(commentRepository.saveAndFlush(any())).thenReturn(comment);
        when(mapper.toResponse(eq(comment), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        service.editComment(actorId, commentId, new EditCommentRequest("a badword"));

        assertThat(comment.getModerationStatus()).isEqualTo("hidden");
        assertThat(comment.getHiddenReason()).isEqualTo("limited_interaction");
    }

    @Test
    void edit_hiddenCommentByAnAuthorNowBlockedByThePostOwner_throwsPostCommentingRestricted() {
        Post post = publishedPost();
        Comment comment = approvedComment(actorId);
        comment.setModerationStatus("hidden");
        comment.setHiddenReason("hidden_words");
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));
        when(blockRepository.existsBetween(actorId, post.getUserId())).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.editComment(
                                        actorId, commentId, new EditCommentRequest("new text")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_COMMENTING_RESTRICTED);
        assertThat(comment.getContent()).isNull();
        verify(commentRepository, never()).saveAndFlush(any());
    }

    @Test
    void edit_hiddenCommentByAnAuthorBlockedByTheParentAuthor_throwsCommentNotFound() {
        UUID parentId = UUID.randomUUID();
        Comment comment = approvedComment(actorId);
        comment.setParentId(parentId);
        comment.setModerationStatus("hidden");
        comment.setHiddenReason("hidden_words");
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(commentRepository.findByIdAndDeletedAtIsNull(parentId))
                .thenReturn(
                        Optional.of(
                                Comment.builder()
                                        .id(parentId)
                                        .postId(postId)
                                        .userId(UUID.randomUUID())
                                        .build()));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        assertThatThrownBy(
                        () ->
                                service.editComment(
                                        actorId, commentId, new EditCommentRequest("new text")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    void create_policyDeny_neverTouchesTheSlowModeKey() {
        CommentServiceImpl slowService = newService(new CommentProperties(30, 24));
        Post post = publishedPost();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(commentRequestTo(post.getUserId())))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));
        when(blockRepository.existsBetween(actorId, post.getUserId())).thenReturn(true);

        assertThatThrownBy(() -> slowService.createComment(actorId, createRequest(null), null))
                .isInstanceOf(AppException.class);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void edit_policyDenyForABlockWithThePostOwner_throwsPostCommentingRestricted() {
        Post post = publishedPost();
        Comment comment = approvedComment(actorId);
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));
        when(blockRepository.existsBetween(actorId, post.getUserId())).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.editComment(
                                        actorId, commentId, new EditCommentRequest("updated")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_COMMENTING_RESTRICTED);
        verify(commentRepository, never()).saveAndFlush(any());
    }

    @Test
    void edit_policyDenyForABlockWithTheParentAuthor_throwsCommentNotFound() {
        UUID parentId = UUID.randomUUID();
        UUID parentAuthorId = UUID.randomUUID();
        Comment comment = approvedComment(actorId);
        comment.setParentId(parentId);
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(commentRepository.findByIdAndDeletedAtIsNull(parentId))
                .thenReturn(
                        Optional.of(
                                Comment.builder()
                                        .id(parentId)
                                        .postId(postId)
                                        .userId(parentAuthorId)
                                        .build()));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(moderationService.check(any())).thenReturn(ModerationResult.approved());
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        assertThatThrownBy(
                        () ->
                                service.editComment(
                                        actorId, commentId, new EditCommentRequest("updated")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
        ArgumentCaptor<InteractionRequest> asked =
                ArgumentCaptor.forClass(InteractionRequest.class);
        verify(interactionPolicy).evaluate(asked.capture());
        assertThat(asked.getValue().secondaryTargetIds()).containsExactly(parentAuthorId);
    }

    @Test
    void likeComment_doesNotStampEditedAt() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(actorId, commentId))
                .thenReturn(false);

        service.likeComment(actorId, commentId);

        assertThat(comment.getEditedAt()).isNull();
    }

    @Test
    void deleteComment_doesNotStampEditedAt() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));

        service.deleteComment(actorId, commentId);

        assertThat(comment.getEditedAt()).isNull();
    }

    @Test
    void deleteComment_notOwner_throwsForbidden() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        assertThatThrownBy(() -> service.deleteComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_FORBIDDEN);
    }

    @Test
    void deleteComment_success_callsSoftDeleteSubtree() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));

        service.deleteComment(actorId, commentId);

        verify(commentRepository).softDeleteSubtree(eq(commentId), any());
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_DELETED_V1),
                        any(),
                        any(),
                        eq(commentId),
                        eq(actorId),
                        anyMap());
    }

    @Test
    void deleteComment_postOwner_returnsAndPublishesTheAffectedRowCount() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(postOwnedBy(actorId)));
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(commentRepository.softDeleteSubtree(eq(commentId), any())).thenReturn(11);

        CommentDeletionScopeResponse response = service.deleteComment(actorId, commentId);

        assertThat(response.deletedCommentCount()).isEqualTo(11);
        ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_DELETED_V1),
                        any(),
                        any(),
                        eq(commentId),
                        eq(actorId),
                        data.capture());
        assertThat(data.getValue()).containsEntry("deletedCommentCount", 11);
    }

    @Test
    void getDeletionScope_postOwner_returnsTheWholeSubtreeCount() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(postOwnedBy(actorId)));
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(commentRepository.countSubtree(commentId)).thenReturn(11);

        assertThat(service.getDeletionScope(actorId, commentId).deletedCommentCount())
                .isEqualTo(11);
    }

    @Test
    void getDeletionScope_ordinaryCaller_countsOnlyWhatTheyCanSee() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(commentRepository.countSubtreeVisibleTo(commentId, actorId)).thenReturn(4);

        assertThat(service.getDeletionScope(actorId, commentId).deletedCommentCount()).isEqualTo(4);
        verify(commentRepository, never()).countSubtree(any());
    }

    @Test
    void deleteComment_ordinaryCaller_returnsTheVisibleCountAndPublishesBoth() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(commentRepository.countSubtreeVisibleTo(commentId, actorId)).thenReturn(4);
        when(commentRepository.countVisibleSubtree(commentId)).thenReturn(3);
        when(commentRepository.softDeleteSubtree(eq(commentId), any())).thenReturn(6);

        CommentDeletionScopeResponse response = service.deleteComment(actorId, commentId);

        assertThat(response.deletedCommentCount()).isEqualTo(4);
        ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_DELETED_V1),
                        any(),
                        any(),
                        eq(commentId),
                        eq(actorId),
                        data.capture());
        assertThat(data.getValue())
                .containsEntry("deletedCommentCount", 6)
                .containsEntry("visibleDeletedCount", 3);
    }

    @Test
    void getDeletionScope_notOwner_throwsNotFoundRatherThanForbidden() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> service.getDeletionScope(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
        verify(commentRepository, never()).countSubtree(any());
    }

    @Test
    void getDeletionScope_admin_isAllowedOnAnotherUsersComment() {
        UUID adminId = UUID.randomUUID();
        UserPrincipal admin = new UserPrincipal(adminId, "admin@test", "ADMIN", "ACTIVE");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                admin, null, admin.getAuthorities()));
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(commentRepository.countSubtree(commentId)).thenReturn(4);

        assertThat(service.getDeletionScope(adminId, commentId).deletedCommentCount()).isEqualTo(4);
    }

    @Test
    void getDeletionScope_missingComment_throwsNotFound() {
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDeletionScope(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    void deleteComment_admin_allowed() {
        UUID adminId = UUID.randomUUID();
        UserPrincipal admin = new UserPrincipal(adminId, "admin@test", "ADMIN", "ACTIVE");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                admin, null, admin.getAuthorities()));
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));

        service.deleteComment(adminId, commentId);

        verify(commentRepository).softDeleteSubtree(eq(commentId), any());
    }

    @Test
    void likeComment_ownComment_persistsAndEnqueues() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(actorId)
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(actorId, commentId))
                .thenReturn(false);

        service.likeComment(actorId, commentId);

        verify(commentLikeRepository).saveAndFlush(any());
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_LIKED_V1),
                        any(),
                        any(),
                        eq(commentId),
                        eq(actorId),
                        anyMap());
    }

    @Test
    void likeComment_hiddenCommentOfAnotherUser_throwsNotFoundAndEnqueuesNothing() {
        Comment hidden =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("hidden")
                        .hiddenReason("hidden_words")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.likeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
        verify(commentLikeRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void unlikeComment_hiddenCommentOfAnotherUser_throwsNotFoundAndEnqueuesNothing() {
        Comment hidden =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("hidden")
                        .hiddenReason("hidden_words")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.unlikeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_FOUND);
        verify(commentLikeRepository, never()).deleteByUserAndComment(any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void likeComment_postNotVisible_throwsForbidden() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(false);

        assertThatThrownBy(() -> service.likeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
        verify(commentLikeRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void likeComment_alreadyLiked_throwsConflict() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(actorId, commentId))
                .thenReturn(true);
        assertThatThrownBy(() -> service.likeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_ALREADY_LIKED);
    }

    @Test
    void likeComment_success_persistsAndEnqueues() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(actorId, commentId))
                .thenReturn(false);

        service.likeComment(actorId, commentId);

        verify(commentLikeRepository).saveAndFlush(any());
        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_LIKED_V1),
                        any(),
                        any(),
                        eq(commentId),
                        eq(actorId),
                        anyMap());
    }

    @Test
    void likeComment_concurrentDuplicateInsert_throwsAlreadyLiked() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.existsByIdUserIdAndIdCommentId(actorId, commentId))
                .thenReturn(false);
        when(commentLikeRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.likeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_ALREADY_LIKED);
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void unlikeComment_notLiked_throwsConflict() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.deleteByUserAndComment(actorId, commentId)).thenReturn(0);
        assertThatThrownBy(() -> service.unlikeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.COMMENT_NOT_LIKED);
    }

    @Test
    void unlikeComment_postNotVisible_throwsForbidden() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(false);

        assertThatThrownBy(() -> service.unlikeComment(actorId, commentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
        verify(commentLikeRepository, never()).deleteByUserAndComment(any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void unlikeComment_success_enqueues() {
        Comment comment =
                Comment.builder()
                        .id(commentId)
                        .postId(postId)
                        .userId(UUID.randomUUID())
                        .moderationStatus("approved")
                        .build();
        when(commentRepository.findByIdAndDeletedAtIsNull(commentId))
                .thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentLikeRepository.deleteByUserAndComment(actorId, commentId)).thenReturn(1);

        service.unlikeComment(actorId, commentId);

        verify(outboxService)
                .enqueue(
                        eq(CommentEventTypes.COMMENT_UNLIKED_V1),
                        any(),
                        any(),
                        eq(commentId),
                        eq(actorId),
                        anyMap());
    }

    @Test
    void listTopLevelComments_exactlyFullPage_hasNextPageFalse() {
        int limit = 3;
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        List<Comment> rows =
                List.of(
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build(),
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build(),
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build());
        when(commentRepository.findTopLikedTopLevel(eq(postId), any(), any()))
                .thenReturn(List.of());
        when(commentRepository.findFirstTopLevelExcluding(eq(postId), any(), any(), any()))
                .thenReturn(rows);
        when(mapper.toResponse(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, limit);

        assertThat(result.getPageInfo().isHasNextPage()).isFalse();
        assertThat(result.getContent()).hasSize(3);
    }

    @Test
    void listTopLevelComments_oneMoreThanLimit_hasNextPageTrue() {
        int limit = 3;
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        List<Comment> rows =
                List.of(
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build(),
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build(),
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build(),
                        Comment.builder().id(UUID.randomUUID()).postId(postId).build());
        when(commentRepository.findTopLikedTopLevel(eq(postId), any(), any()))
                .thenReturn(List.of());
        when(commentRepository.findFirstTopLevelExcluding(eq(postId), any(), any(), any()))
                .thenReturn(rows);
        when(mapper.toResponse(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, limit);

        assertThat(result.getPageInfo().isHasNextPage()).isTrue();
        assertThat(result.getContent()).hasSize(3);
    }

    @Test
    void listTopLevelComments_threeEligible_pinsExactlyThreeAheadOfTheBody() {
        List<Comment> pinned = comments(3);
        List<Comment> body = comments(2);
        stubFirstPage(pinned, body);

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getContent().subList(0, 3))
                .allSatisfy(c -> assertThat(c.pinned()).isTrue());
        assertThat(result.getContent().subList(3, 5))
                .allSatisfy(c -> assertThat(c.pinned()).isFalse());
    }

    @Test
    void listTopLevelComments_twoEligible_pinsTwo() {
        stubFirstPage(comments(2), comments(4));

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        assertThat(result.getContent()).hasSize(6);
        assertThat(result.getContent().stream().filter(CommentResponse::pinned)).hasSize(2);
    }

    @Test
    void listTopLevelComments_oneEligible_pinsOne() {
        stubFirstPage(comments(1), comments(4));

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getContent().stream().filter(CommentResponse::pinned)).hasSize(1);
    }

    @Test
    void listTopLevelComments_noEligible_pinsNothingAndPageIsUnchanged() {
        stubFirstPage(List.of(), comments(4));

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        assertThat(result.getContent()).hasSize(4);
        assertThat(result.getContent()).noneMatch(CommentResponse::pinned);
    }

    @Test
    void listTopLevelComments_pinnedIdsAreExcludedFromTheBodyQuery() {
        List<Comment> pinned = comments(3);
        stubFirstPage(pinned, comments(2));
        ArgumentCaptor<UUID[]> excluded = ArgumentCaptor.forClass(UUID[].class);

        service.listTopLevelComments(actorId, postId, null, null, 5);

        verify(commentRepository)
                .findFirstTopLevelExcluding(eq(postId), excluded.capture(), any(), any());
        assertThat(excluded.getValue())
                .containsExactlyElementsOf(pinned.stream().map(Comment::getId).toList());
    }

    @Test
    void listTopLevelComments_pinnedBlockIsAdditionalToTheRequestedLimit() {
        stubFirstPage(comments(3), comments(6));

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        // Body is trimmed to the requested limit of 5; the three pinned rows sit on top of it.
        assertThat(result.getContent()).hasSize(8);
        assertThat(result.getPageInfo().isHasNextPage()).isTrue();
    }

    @Test
    void listTopLevelComments_withCursor_resolvesPinnedIdsToExcludeButPrependsNoBlock() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        List<Comment> pinned = comments(3);
        when(commentRepository.findTopLikedTopLevel(eq(postId), eq(actorId), any()))
                .thenReturn(pinned);
        when(commentRepository.findTopLevelBefore(eq(postId), any(), any(), any(), any(), any()))
                .thenReturn(comments(2));
        when(mapper.toResponse(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(sampleResponse());

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, SECOND_PAGE_CURSOR, 5);

        // The pinned block is prepended to page one only, but its ids are still needed here: a
        // pinned comment old enough to fall on this page must be excluded, or it is returned
        // twice across the stream.
        ArgumentCaptor<UUID[]> excluded = ArgumentCaptor.forClass(UUID[].class);
        verify(commentRepository)
                .findTopLevelBefore(eq(postId), excluded.capture(), any(), any(), any(), any());
        assertThat(excluded.getValue())
                .containsExactlyElementsOf(pinned.stream().map(Comment::getId).toList());
        verify(commentRepository, never()).findFirstTopLevelExcluding(any(), any(), any(), any());
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).noneMatch(CommentResponse::pinned);
    }

    @Test
    void listTopLevelComments_cursorIssuedBeforePinningWasAdded_stillDecodes() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentRepository.findTopLevelBefore(eq(postId), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, SECOND_PAGE_CURSOR, 5);

        assertThat(result.getContent()).isEmpty();
        verify(commentRepository).findTopLevelBefore(eq(postId), any(), any(), any(), any(), any());
    }

    @Test
    void listTopLevelComments_pinnedBlockDoesNotAppearTwiceOnTheFirstPage() {
        List<Comment> pinned = comments(3);
        // The repository applies the exclusion in SQL, so the body it returns already omits them.
        stubFirstPage(pinned, comments(4));

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        assertThat(result.getContent()).extracting(CommentResponse::id).doesNotHaveDuplicates();
    }

    @Test
    void listTopLevelComments_cursorsAreDerivedFromTheBodyNotThePinnedBlock() {
        List<Comment> body = comments(2);
        stubFirstPage(comments(3), body);

        CursorPageResponse<CommentResponse> result =
                service.listTopLevelComments(actorId, postId, null, null, 5);

        assertThat(result.getPageInfo().getStartCursor())
                .isEqualTo(
                        CursorCodec.encode(
                                new Cursor(0L, body.get(0).getId()),
                                CursorScope.COMMENTS_TOP_LEVEL));
        assertThat(result.getPageInfo().getEndCursor())
                .isEqualTo(
                        CursorCodec.encode(
                                new Cursor(0L, body.get(1).getId()),
                                CursorScope.COMMENTS_TOP_LEVEL));
    }

    // Distinct ids per row so the mapper stub, which returns one shared response, cannot mask an
    // ordering or duplication defect: assertions read ids off the entities, not the response.
    private List<Comment> comments(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(
                        i ->
                                Comment.builder()
                                        .id(UUID.randomUUID())
                                        .postId(postId)
                                        .createdAt(EPOCH)
                                        .build())
                .toList();
    }

    private void stubFirstPage(List<Comment> pinned, List<Comment> body) {
        when(postRepository.findById(postId)).thenReturn(Optional.of(publishedPost()));
        when(postVisibilityService.isVisibleTo(eq(actorId), any())).thenReturn(true);
        when(commentRepository.findTopLikedTopLevel(eq(postId), any(), any())).thenReturn(pinned);
        when(commentRepository.findFirstTopLevelExcluding(eq(postId), any(), any(), any()))
                .thenReturn(body);
        when(mapper.toResponse(any(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            Comment c = invocation.getArgument(0);
                            return sampleResponseFor(c.getId());
                        });
    }

    private CommentResponse sampleResponseFor(UUID id) {
        UserSummaryResponse author =
                new UserSummaryResponse(actorId, "actor", "Actor", null, false);
        return new CommentResponse(
                id,
                postId,
                author,
                null,
                null,
                (short) 0,
                "hello world",
                0,
                false,
                false,
                0,
                null,
                null,
                null,
                false);
    }

    private CommentResponse sampleResponse() {
        UserSummaryResponse author =
                new UserSummaryResponse(actorId, "actor", "Actor", null, false);
        return new CommentResponse(
                commentId,
                postId,
                author,
                null,
                null,
                (short) 0,
                "hello world",
                0,
                false,
                false,
                0,
                null,
                null,
                null,
                false);
    }

    // Mirrors CommentServiceImpl's request-hash formula so the replay test can match the stored
    // row.
    private static String sha256(String input) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
