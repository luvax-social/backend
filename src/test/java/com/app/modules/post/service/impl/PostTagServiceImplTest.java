package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.response.UserSummaryResponse;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.post.dto.request.PostTagInput;
import com.app.modules.post.dto.response.PostMediaTagResponse;
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

@ExtendWith(MockitoExtension.class)
class PostTagServiceImplTest {

    @Mock private PostUserTagRepository tagRepository;
    @Mock private PostRepository postRepository;
    @Mock private PostUserRepository postUserRepository;
    @Mock private InteractionPolicyService interactionPolicyService;
    @Mock private SystemSettingService systemSettingService;
    @Mock private OutboxService outboxService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private SocialService socialService;
    @Mock private PostPreviewService postPreviewService;
    @Mock private PostVisibilityService postVisibilityService;

    private PostTagServiceImpl service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();
    private final UUID secondMediaId = UUID.randomUUID();
    private final UUID taggedId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostTagServiceImpl(
                        tagRepository,
                        postRepository,
                        postUserRepository,
                        interactionPolicyService,
                        systemSettingService,
                        outboxService,
                        userSummaryService,
                        socialService,
                        postPreviewService,
                        postVisibilityService);
        lenient()
                .when(systemSettingService.getRequiredLong("max_post_tags_per_media"))
                .thenReturn(20L);
        lenient().when(tagRepository.findByPostId(postId)).thenReturn(List.of());
        lenient()
                .when(tagRepository.save(any(PostUserTag.class)))
                .thenAnswer(
                        invocation -> {
                            PostUserTag tag = invocation.getArgument(0);
                            if (tag.getId() == null) {
                                tag.setId(UUID.randomUUID());
                            }
                            return tag;
                        });
        lenient()
                .when(postUserRepository.findAllByIdInAndDeletedAtIsNull(anyCollection()))
                .thenAnswer(
                        invocation -> {
                            java.util.Collection<UUID> ids = invocation.getArgument(0);
                            return ids.stream()
                                    .map(
                                            id ->
                                                    User.builder()
                                                            .id(id)
                                                            .status(UserStatus.ACTIVE)
                                                            .build())
                                    .toList();
                        });
    }

    private Post post(PostStatus status) {
        List<PostMedia> media =
                new ArrayList<>(
                        List.of(
                                PostMedia.builder().id(mediaId).position((short) 0).build(),
                                PostMedia.builder().id(secondMediaId).position((short) 1).build()));
        return Post.builder().id(postId).userId(ownerId).status(status).media(media).build();
    }

    private static PostTagInput input(int position, UUID userId) {
        return new PostTagInput(position, userId, new BigDecimal("0.5"), new BigDecimal("0.25"));
    }

    private void policyAnswers(InteractionOutcome outcome, InteractionReason reason) {
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(new InteractionDecision(outcome, reason));
    }

    private static AppException failureOf(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected an AppException");
    }

    private PostUserTag savedTag() {
        ArgumentCaptor<PostUserTag> captor = ArgumentCaptor.forClass(PostUserTag.class);
        verify(tagRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void replaceTags_allowedTagOnADraft_isApprovedAndNothingIsAnnounced() {
        policyAnswers(InteractionOutcome.ALLOW, InteractionReason.NONE);

        service.replaceTags(post(PostStatus.DRAFT), List.of(input(0, taggedId)));

        PostUserTag tag = savedTag();
        assertThat(tag.getStatus()).isEqualTo(TagStatus.APPROVED);
        assertThat(tag.getPostMediaId()).isEqualTo(mediaId);
        assertThat(tag.getTaggedUserId()).isEqualTo(taggedId);
        assertThat(tag.getCreatedBy()).isEqualTo(ownerId);
        assertThat(tag.getXPosition()).isEqualByComparingTo("0.5");
        assertThat(tag.getYPosition()).isEqualByComparingTo("0.25");
        verifyNoInteractions(outboxService);
    }

    @Test
    void replaceTags_manualApprovalOnAPublishedPost_waitsAndNotifies() {
        policyAnswers(InteractionOutcome.ROUTE_TO_REQUESTS, InteractionReason.APPROVAL_REQUIRED);

        service.replaceTags(post(PostStatus.PUBLISHED), List.of(input(1, taggedId)));

        PostUserTag tag = savedTag();
        assertThat(tag.getStatus()).isEqualTo(TagStatus.PENDING);
        assertThat(tag.getPostMediaId()).isEqualTo(secondMediaId);
        Map<String, Object> data = createdEvent();
        assertThat(data).containsEntry("outcome", "pending").containsEntry("notify", true);
    }

    @Test
    void replaceTags_limitHidTheTag_isPendingButNeverNotified() {
        policyAnswers(
                InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS,
                InteractionReason.LIMITED_NON_FOLLOWER);

        service.replaceTags(post(PostStatus.PUBLISHED), List.of(input(0, taggedId)));

        assertThat(savedTag().getStatus()).isEqualTo(TagStatus.PENDING);
        assertThat(createdEvent()).containsEntry("notify", false);
    }

    @Test
    void replaceTags_ownerTaggingThemselves_isApprovedWithoutNotice() {
        policyAnswers(InteractionOutcome.ALLOW, InteractionReason.SELF);

        service.replaceTags(post(PostStatus.PUBLISHED), List.of(input(0, ownerId)));

        assertThat(createdEvent()).containsEntry("notify", false);
    }

    @Test
    void replaceTags_blockedPerson_answersAsAnUnknownUser() {
        policyAnswers(InteractionOutcome.DENY, InteractionReason.BLOCKED);

        AppException failure =
                failureOf(
                        () ->
                                service.replaceTags(
                                        post(PostStatus.DRAFT), List.of(input(0, taggedId))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        verify(tagRepository, never()).save(any());
    }

    @Test
    void replaceTags_personWhoAllowsNobody_saysSo() {
        policyAnswers(InteractionOutcome.DENY, InteractionReason.PERMISSION_NOBODY);

        AppException failure =
                failureOf(
                        () ->
                                service.replaceTags(
                                        post(PostStatus.DRAFT), List.of(input(0, taggedId))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_TAG_NOT_ALLOWED);
    }

    @Test
    void replaceTags_unknownOrInactiveAccount_isNotFound() {
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(List.of(User.builder().id(taggedId).status(UserStatus.BANNED).build()));

        AppException failure =
                failureOf(
                        () ->
                                service.replaceTags(
                                        post(PostStatus.DRAFT), List.of(input(0, taggedId))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void replaceTags_mediaPositionTheDraftLacks_isBadRequest() {
        AppException failure =
                failureOf(
                        () ->
                                service.replaceTags(
                                        post(PostStatus.DRAFT), List.of(input(2, taggedId))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void replaceTags_samePersonTwiceOnOneItem_isBadRequest() {
        AppException failure =
                failureOf(
                        () ->
                                service.replaceTags(
                                        post(PostStatus.DRAFT),
                                        List.of(input(0, taggedId), input(0, taggedId))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void replaceTags_samePersonOnTwoItems_isAllowed() {
        policyAnswers(InteractionOutcome.ALLOW, InteractionReason.NONE);

        service.replaceTags(
                post(PostStatus.DRAFT), List.of(input(0, taggedId), input(1, taggedId)));

        verify(tagRepository, org.mockito.Mockito.times(2)).save(any(PostUserTag.class));
    }

    @Test
    void replaceTags_moreThanTheLimitOnOneItem_isRefused() {
        when(systemSettingService.getRequiredLong("max_post_tags_per_media")).thenReturn(2L);
        List<PostTagInput> tags =
                List.of(
                        input(0, UUID.randomUUID()),
                        input(0, UUID.randomUUID()),
                        input(0, UUID.randomUUID()));

        AppException failure = failureOf(() -> service.replaceTags(post(PostStatus.DRAFT), tags));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_TAG_LIMIT_REACHED);
    }

    @Test
    void replaceTags_existingTagThatIsKept_keepsItsStatusAndOnlyMoves() {
        PostUserTag existing =
                PostUserTag.builder()
                        .id(UUID.randomUUID())
                        .postId(postId)
                        .postMediaId(mediaId)
                        .taggedUserId(taggedId)
                        .status(TagStatus.APPROVED)
                        .xPosition(new BigDecimal("0.1"))
                        .yPosition(new BigDecimal("0.1"))
                        .build();
        when(tagRepository.findByPostId(postId)).thenReturn(List.of(existing));

        service.replaceTags(post(PostStatus.PUBLISHED), List.of(input(0, taggedId)));

        assertThat(existing.getStatus()).isEqualTo(TagStatus.APPROVED);
        assertThat(existing.getXPosition()).isEqualByComparingTo("0.5");
        verify(tagRepository, never()).save(any());
        verifyNoInteractions(interactionPolicyService, outboxService);
    }

    @Test
    void replaceTags_tagLeftOutOfThePublishedSet_isDeletedAndRecorded() {
        PostUserTag existing =
                PostUserTag.builder()
                        .id(UUID.randomUUID())
                        .postId(postId)
                        .postMediaId(mediaId)
                        .taggedUserId(taggedId)
                        .status(TagStatus.APPROVED)
                        .build();
        when(tagRepository.findByPostId(postId)).thenReturn(List.of(existing));

        service.replaceTags(post(PostStatus.PUBLISHED), List.of());

        verify(tagRepository).delete(existing);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_TAG_REMOVED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        anyMap());
    }

    @Test
    void replaceTags_tagLeftOutOfADraft_isDeletedSilently() {
        PostUserTag existing =
                PostUserTag.builder()
                        .id(UUID.randomUUID())
                        .postId(postId)
                        .postMediaId(mediaId)
                        .taggedUserId(taggedId)
                        .status(TagStatus.PENDING)
                        .build();
        when(tagRepository.findByPostId(postId)).thenReturn(List.of(existing));

        service.replaceTags(post(PostStatus.DRAFT), List.of());

        verify(tagRepository).delete(existing);
        verifyNoInteractions(outboxService);
    }

    @Test
    void announceOnPublication_decidesAgain_dropsTheDeniedAndAnnouncesTheRest() {
        UUID blockedId = UUID.randomUUID();
        PostUserTag allowed = tag(taggedId, TagStatus.PENDING);
        PostUserTag denied = tag(blockedId, TagStatus.APPROVED);
        when(tagRepository.findByPostId(postId)).thenReturn(List.of(allowed, denied));
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenAnswer(
                        invocation -> {
                            InteractionRequest request = invocation.getArgument(0);
                            assertThat(request.type()).isEqualTo(InteractionType.TAG);
                            return request.targetUserId().equals(blockedId)
                                    ? new InteractionDecision(
                                            InteractionOutcome.DENY, InteractionReason.BLOCKED)
                                    : new InteractionDecision(
                                            InteractionOutcome.ALLOW, InteractionReason.NONE);
                        });

        service.announceOnPublication(post(PostStatus.PUBLISHED));

        verify(tagRepository).delete(denied);
        // An undecided tag takes the outcome the policy gives now.
        assertThat(allowed.getStatus()).isEqualTo(TagStatus.APPROVED);
        verify(outboxService, org.mockito.Mockito.times(1))
                .enqueue(
                        eq(PostEventTypes.POST_TAG_CREATED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        anyMap());
    }

    @Test
    void announceOnPublication_approvalAlreadyGiven_isNeverRevoked() {
        PostUserTag approved = tag(taggedId, TagStatus.APPROVED);
        when(tagRepository.findByPostId(postId)).thenReturn(List.of(approved));
        policyAnswers(InteractionOutcome.ROUTE_TO_REQUESTS, InteractionReason.APPROVAL_REQUIRED);

        service.announceOnPublication(post(PostStatus.PUBLISHED));

        assertThat(approved.getStatus()).isEqualTo(TagStatus.APPROVED);
    }

    @Test
    void approve_pendingTagOfTheViewer_becomesApprovedAndIsRecorded() {
        PostUserTag pending = tag(taggedId, TagStatus.PENDING);
        stubVisibleTag(pending);

        service.approve(taggedId, postId, pending.getId());

        assertThat(pending.getStatus()).isEqualTo(TagStatus.APPROVED);
        assertThat(pending.getApprovedAt()).isNotNull();
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_TAG_APPROVED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(taggedId),
                        anyMap());
    }

    @Test
    void approve_alreadyApproved_changesNothing() {
        PostUserTag approved = tag(taggedId, TagStatus.APPROVED);
        stubVisibleTag(approved);

        service.approve(taggedId, postId, approved.getId());

        verifyNoInteractions(outboxService);
    }

    @Test
    void approve_tagOfSomeoneElse_isNotFound() {
        PostUserTag pending = tag(taggedId, TagStatus.PENDING);
        stubVisibleTag(pending);

        AppException failure =
                failureOf(() -> service.approve(UUID.randomUUID(), postId, pending.getId()));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_TAG_NOT_FOUND);
    }

    @Test
    void approve_postNoLongerPublished_isNotFound() {
        PostUserTag pending = tag(taggedId, TagStatus.PENDING);
        when(tagRepository.findByIdAndPostId(pending.getId(), postId))
                .thenReturn(Optional.of(pending));
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.ARCHIVED)));

        AppException failure = failureOf(() -> service.approve(taggedId, postId, pending.getId()));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_TAG_NOT_FOUND);
    }

    @Test
    void remove_byTheTaggedPerson_deletesAndRecordsWhoRemovedIt() {
        PostUserTag approved = tag(taggedId, TagStatus.APPROVED);
        when(tagRepository.findByIdAndPostId(approved.getId(), postId))
                .thenReturn(Optional.of(approved));
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.PUBLISHED)));

        service.remove(taggedId, postId, approved.getId());

        verify(tagRepository).delete(approved);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_TAG_REMOVED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(taggedId),
                        data.capture());
        assertThat(data.getValue()).containsEntry("removedBy", "tagged");
    }

    @Test
    void remove_byAStranger_isNotFound() {
        PostUserTag approved = tag(taggedId, TagStatus.APPROVED);
        when(tagRepository.findByIdAndPostId(approved.getId(), postId))
                .thenReturn(Optional.of(approved));
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.PUBLISHED)));

        AppException failure =
                failureOf(() -> service.remove(UUID.randomUUID(), postId, approved.getId()));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_TAG_NOT_FOUND);
        verify(tagRepository, never()).delete(any());
    }

    @Test
    void remove_onADraft_recordsNothing() {
        PostUserTag pending = tag(taggedId, TagStatus.PENDING);
        when(tagRepository.findByIdAndPostId(pending.getId(), postId))
                .thenReturn(Optional.of(pending));
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.DRAFT)));

        service.remove(ownerId, postId, pending.getId());

        verify(tagRepository).delete(pending);
        verifyNoInteractions(outboxService);
    }

    @Test
    void loadForPosts_pendingTagIsShownOnlyToTheOwnerAndTheTaggedPerson() {
        PostUserTag pending = tag(taggedId, TagStatus.PENDING);
        pending.setXPosition(new BigDecimal("0.3"));
        pending.setYPosition(new BigDecimal("0.7"));
        when(tagRepository.findByPostIdIn(anyCollection())).thenReturn(List.of(pending));
        when(socialService.findBlockedEitherDirection(any())).thenReturn(Set.of());
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(
                        Map.of(
                                taggedId,
                                new UserSummaryResponse(taggedId, "tagged", "T", null, false)));
        Post post = post(PostStatus.PUBLISHED);

        Map<UUID, List<PostMediaTagResponse>> forOwner =
                service.loadForPosts(ownerId, List.of(post));
        Map<UUID, List<PostMediaTagResponse>> forTagged =
                service.loadForPosts(taggedId, List.of(post));
        Map<UUID, List<PostMediaTagResponse>> forStranger =
                service.loadForPosts(UUID.randomUUID(), List.of(post));

        assertThat(forOwner.get(mediaId)).hasSize(1);
        assertThat(forTagged.get(mediaId)).hasSize(1);
        assertThat(forStranger).isEmpty();
    }

    @Test
    void loadForPosts_approvedTagOfAnAccountBlockedWithTheViewer_isLeftOut() {
        PostUserTag approved = tag(taggedId, TagStatus.APPROVED);
        when(tagRepository.findByPostIdIn(anyCollection())).thenReturn(List.of(approved));
        UUID viewer = UUID.randomUUID();
        when(socialService.findBlockedEitherDirection(viewer)).thenReturn(Set.of(taggedId));
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(
                        Map.of(
                                taggedId,
                                new UserSummaryResponse(taggedId, "tagged", "T", null, false)));

        assertThat(service.loadForPosts(viewer, List.of(post(PostStatus.PUBLISHED)))).isEmpty();
    }

    @Test
    void loadForPosts_deletedAccount_isLeftOut() {
        PostUserTag approved = tag(taggedId, TagStatus.APPROVED);
        when(tagRepository.findByPostIdIn(anyCollection())).thenReturn(List.of(approved));
        when(socialService.findBlockedEitherDirection(any())).thenReturn(Set.of());
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(
                        Map.of(
                                taggedId,
                                new UserSummaryResponse(taggedId, null, "Deleted", null, false)));

        assertThat(service.loadForPosts(ownerId, List.of(post(PostStatus.PUBLISHED)))).isEmpty();
    }

    private PostUserTag tag(UUID userId, TagStatus status) {
        return PostUserTag.builder()
                .id(UUID.randomUUID())
                .postId(postId)
                .postMediaId(mediaId)
                .taggedUserId(userId)
                .status(status)
                .build();
    }

    private void stubVisibleTag(PostUserTag tag) {
        Post post = post(PostStatus.PUBLISHED);
        when(tagRepository.findByIdAndPostId(tag.getId(), postId)).thenReturn(Optional.of(tag));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        lenient().when(postVisibilityService.isVisibleTo(any(), any(Post.class))).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createdEvent() {
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_TAG_CREATED_V1),
                        eq(PostEventTypes.POST_TAG_CREATED_V1),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        data.capture());
        return data.getValue();
    }
}
