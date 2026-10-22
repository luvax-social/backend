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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.response.UserSummaryResponse;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.entity.PostCollaboratorId;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostCollaborationService.CollaborationView;
import com.app.modules.post.service.PostPreviewService;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class PostCollaborationServiceImplTest {

    @Mock private PostCollaboratorRepository collaboratorRepository;
    @Mock private PostRepository postRepository;
    @Mock private PostUserRepository postUserRepository;
    @Mock private InteractionPolicyService interactionPolicyService;
    @Mock private SystemSettingService systemSettingService;
    @Mock private OutboxService outboxService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private PostPreviewService postPreviewService;
    @Mock private PostIndexEventPublisher postIndexEventPublisher;
    @Mock private EntityManager entityManager;

    private PostCollaborationServiceImpl service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID inviteeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostCollaborationServiceImpl(
                        collaboratorRepository,
                        postRepository,
                        postUserRepository,
                        interactionPolicyService,
                        systemSettingService,
                        outboxService,
                        userSummaryService,
                        postPreviewService,
                        postIndexEventPublisher);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        lenient()
                .when(systemSettingService.getRequiredLong("max_post_collaborators"))
                .thenReturn(3L);
        lenient().when(collaboratorRepository.findByPostId(postId)).thenReturn(List.of());
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
        lenient()
                .when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(
                        new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        lenient().when(collaboratorRepository.find(any(), any())).thenReturn(Optional.empty());
    }

    private Post post(PostStatus status) {
        return Post.builder().id(postId).userId(ownerId).status(status).build();
    }

    private PostCollaborator row(UUID userId, CollaborationStatus status) {
        return PostCollaborator.builder()
                .id(new PostCollaboratorId(postId, userId))
                .status(status)
                .invitedBy(ownerId)
                .build();
    }

    private static AppException failureOf(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected an AppException");
    }

    @Test
    void replaceInvites_newInviteeOnADraft_isStoredButNotAnnounced() {
        service.replaceInvites(post(PostStatus.DRAFT), List.of(inviteeId));

        org.mockito.ArgumentCaptor<PostCollaborator> saved =
                org.mockito.ArgumentCaptor.forClass(PostCollaborator.class);
        verify(collaboratorRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CollaborationStatus.INVITED);
        assertThat(saved.getValue().getUserId()).isEqualTo(inviteeId);
        assertThat(saved.getValue().getInvitedBy()).isEqualTo(ownerId);
        verifyNoInteractions(outboxService);
    }

    @Test
    void replaceInvites_newInviteeOnAPublishedPost_isAnnouncedAtOnce() {
        service.replaceInvites(post(PostStatus.PUBLISHED), List.of(inviteeId));

        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_COLLABORATION_INVITED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        anyMap());
    }

    @Test
    void replaceInvites_ownerAsInvitee_isBadRequest() {
        AppException failure =
                failureOf(() -> service.replaceInvites(post(PostStatus.DRAFT), List.of(ownerId)));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void replaceInvites_sameAccountTwice_isBadRequest() {
        AppException failure =
                failureOf(
                        () ->
                                service.replaceInvites(
                                        post(PostStatus.DRAFT), List.of(inviteeId, inviteeId)));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void replaceInvites_moreThanTheLimit_isRefused() {
        List<UUID> four =
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        AppException failure =
                failureOf(() -> service.replaceInvites(post(PostStatus.DRAFT), four));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_COLLABORATOR_LIMIT_REACHED);
    }

    @Test
    void replaceInvites_blockedAccount_answersAsAnUnknownUser() {
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        AppException failure =
                failureOf(() -> service.replaceInvites(post(PostStatus.DRAFT), List.of(inviteeId)));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        verify(collaboratorRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaceInvites_policyIsAskedForACollaborationInvite() {
        service.replaceInvites(post(PostStatus.DRAFT), List.of(inviteeId));

        org.mockito.ArgumentCaptor<InteractionRequest> request =
                org.mockito.ArgumentCaptor.forClass(InteractionRequest.class);
        verify(interactionPolicyService).evaluate(request.capture());
        assertThat(request.getValue().type()).isEqualTo(InteractionType.COLLABORATION_INVITE);
        assertThat(request.getValue().actorId()).isEqualTo(ownerId);
        assertThat(request.getValue().targetUserId()).isEqualTo(inviteeId);
    }

    @Test
    void replaceInvites_unknownAccount_isNotFound() {
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(List.of());

        AppException failure =
                failureOf(() -> service.replaceInvites(post(PostStatus.DRAFT), List.of(inviteeId)));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void replaceInvites_accountLeftOut_isRemovedAndAnAcceptedCoAuthorRefreshesTheIndex() {
        PostCollaborator accepted = row(inviteeId, CollaborationStatus.ACCEPTED);
        when(collaboratorRepository.findByPostId(postId)).thenReturn(List.of(accepted));
        Post post = post(PostStatus.PUBLISHED);

        service.replaceInvites(post, List.of());

        assertThat(accepted.getStatus()).isEqualTo(CollaborationStatus.REMOVED);
        assertThat(accepted.getRespondedAt()).isNotNull();
        verify(postIndexEventPublisher).enqueueUpsert(post);
    }

    @Test
    void replaceInvites_withdrawingAPendingInvitation_doesNotTouchTheIndex() {
        PostCollaborator invited = row(inviteeId, CollaborationStatus.INVITED);
        when(collaboratorRepository.findByPostId(postId)).thenReturn(List.of(invited));

        service.replaceInvites(post(PostStatus.PUBLISHED), List.of());

        assertThat(invited.getStatus()).isEqualTo(CollaborationStatus.REMOVED);
        verifyNoInteractions(postIndexEventPublisher);
    }

    @Test
    void replaceInvites_accountThatDeclinedBefore_isInvitedAnewThroughTheReinviteQuery() {
        PostCollaborator declined = row(inviteeId, CollaborationStatus.DECLINED);
        when(collaboratorRepository.findByPostId(postId)).thenReturn(List.of(declined));

        service.replaceInvites(post(PostStatus.PUBLISHED), List.of(inviteeId));

        verify(collaboratorRepository).reinvite(postId, inviteeId, ownerId);
        verify(entityManager).refresh(declined);
        verify(collaboratorRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaceInvites_accountAlreadyInvited_isLeftAlone() {
        when(collaboratorRepository.findByPostId(postId))
                .thenReturn(List.of(row(inviteeId, CollaborationStatus.INVITED)));

        service.replaceInvites(post(PostStatus.PUBLISHED), List.of(inviteeId));

        verify(collaboratorRepository, never()).saveAndFlush(any());
        verify(collaboratorRepository, never()).reinvite(any(), any(), any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void announceOnPublication_announcesEachInvitationThatSurvives() {
        UUID blockedId = UUID.randomUUID();
        PostCollaborator fine = row(inviteeId, CollaborationStatus.INVITED);
        PostCollaborator refused = row(blockedId, CollaborationStatus.INVITED);
        when(collaboratorRepository.findByPostId(postId)).thenReturn(List.of(fine, refused));
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenAnswer(
                        invocation -> {
                            InteractionRequest request = invocation.getArgument(0);
                            return request.targetUserId().equals(blockedId)
                                    ? new InteractionDecision(
                                            InteractionOutcome.DENY, InteractionReason.BLOCKED)
                                    : new InteractionDecision(
                                            InteractionOutcome.ALLOW, InteractionReason.NONE);
                        });

        service.announceOnPublication(post(PostStatus.PUBLISHED));

        assertThat(refused.getStatus()).isEqualTo(CollaborationStatus.REMOVED);
        assertThat(fine.getStatus()).isEqualTo(CollaborationStatus.INVITED);
        verify(outboxService, org.mockito.Mockito.times(1))
                .enqueue(
                        eq(PostEventTypes.POST_COLLABORATION_INVITED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        anyMap());
    }

    @Test
    void accept_openInvitationOnAPublishedPost_makesTheInviteeACoAuthorAndRefreshesTheIndex() {
        Post post = post(PostStatus.PUBLISHED);
        PostCollaborator invited = row(inviteeId, CollaborationStatus.INVITED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(collaboratorRepository.find(postId, inviteeId)).thenReturn(Optional.of(invited));

        service.accept(inviteeId, postId);

        assertThat(invited.getStatus()).isEqualTo(CollaborationStatus.ACCEPTED);
        assertThat(invited.getAcceptedAt()).isNotNull();
        assertThat(invited.getRespondedAt()).isNotNull();
        verify(postIndexEventPublisher).enqueueUpsert(post);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_COLLABORATION_ACCEPTED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(inviteeId),
                        anyMap());
    }

    @Test
    void accept_postNotPublished_isNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.ARCHIVED)));

        AppException failure = failureOf(() -> service.accept(inviteeId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
    }

    @Test
    void accept_noOpenInvitation_isNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.PUBLISHED)));
        when(collaboratorRepository.find(postId, inviteeId))
                .thenReturn(Optional.of(row(inviteeId, CollaborationStatus.DECLINED)));

        AppException failure = failureOf(() -> service.accept(inviteeId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
    }

    @Test
    void accept_ownerBlockedTheInviteeSinceTheInvitation_isNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.PUBLISHED)));
        when(collaboratorRepository.find(postId, inviteeId))
                .thenReturn(Optional.of(row(inviteeId, CollaborationStatus.INVITED)));
        when(interactionPolicyService.evaluate(any(InteractionRequest.class)))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        AppException failure = failureOf(() -> service.accept(inviteeId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
    }

    @Test
    void decline_openInvitation_isRecordedWithoutTouchingTheIndex() {
        PostCollaborator invited = row(inviteeId, CollaborationStatus.INVITED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.PUBLISHED)));
        when(collaboratorRepository.find(postId, inviteeId)).thenReturn(Optional.of(invited));

        service.decline(inviteeId, postId);

        assertThat(invited.getStatus()).isEqualTo(CollaborationStatus.DECLINED);
        verifyNoInteractions(postIndexEventPublisher);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_COLLABORATION_DECLINED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(inviteeId),
                        anyMap());
    }

    @Test
    void leave_acceptedCoAuthor_keepsTheAcceptanceTimeAndRefreshesTheIndex() {
        Post post = post(PostStatus.PUBLISHED);
        PostCollaborator accepted = row(inviteeId, CollaborationStatus.ACCEPTED);
        java.time.OffsetDateTime acceptedAt =
                java.time.OffsetDateTime.parse("2026-10-01T10:00:00Z");
        accepted.setAcceptedAt(acceptedAt);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(collaboratorRepository.find(postId, inviteeId)).thenReturn(Optional.of(accepted));

        service.leave(inviteeId, postId);

        assertThat(accepted.getStatus()).isEqualTo(CollaborationStatus.LEFT);
        assertThat(accepted.getAcceptedAt()).isEqualTo(acceptedAt);
        verify(postIndexEventPublisher).enqueueUpsert(post);
    }

    @Test
    void leave_notACoAuthor_isNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(post(PostStatus.PUBLISHED)));
        when(collaboratorRepository.find(postId, inviteeId))
                .thenReturn(Optional.of(row(inviteeId, CollaborationStatus.INVITED)));

        AppException failure = failureOf(() -> service.leave(inviteeId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_COLLABORATION_NOT_FOUND);
    }

    @Test
    void loadForPosts_acceptedIsShownToEveryoneButAnInvitationOnlyToTheOwnerAndTheInvitee() {
        UUID acceptedId = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(collaboratorRepository.findByPostIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                row(acceptedId, CollaborationStatus.ACCEPTED),
                                row(inviteeId, CollaborationStatus.INVITED),
                                row(UUID.randomUUID(), CollaborationStatus.DECLINED)));
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(
                        Map.of(
                                acceptedId,
                                summary(acceptedId, "accepted"),
                                inviteeId,
                                summary(inviteeId, "invited")));
        Post post = post(PostStatus.PUBLISHED);

        CollaborationView forStranger = service.loadForPosts(stranger, List.of(post)).get(postId);
        CollaborationView forOwner = service.loadForPosts(ownerId, List.of(post)).get(postId);
        CollaborationView forInvitee = service.loadForPosts(inviteeId, List.of(post)).get(postId);

        assertThat(forStranger.collaborators())
                .extracting(c -> c.user().username())
                .containsExactly("accepted");
        assertThat(forStranger.viewerStatus()).isNull();
        assertThat(forOwner.collaborators())
                .extracting(c -> c.user().username())
                .containsExactlyInAnyOrder("accepted", "invited");
        assertThat(forInvitee.collaborators())
                .extracting(c -> c.user().username())
                .containsExactlyInAnyOrder("accepted", "invited");
        assertThat(forInvitee.viewerStatus()).isEqualTo(CollaborationStatus.INVITED);
    }

    @Test
    void loadForPosts_leftAndRemovedAccounts_areNeverShownAndGiveNoViewerStatus() {
        when(collaboratorRepository.findByPostIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                row(inviteeId, CollaborationStatus.LEFT),
                                row(UUID.randomUUID(), CollaborationStatus.REMOVED)));
        when(userSummaryService.loadSummaries(anyCollection())).thenReturn(Map.of());

        assertThat(service.loadForPosts(inviteeId, List.of(post(PostStatus.PUBLISHED)))).isEmpty();
    }

    private static UserSummaryResponse summary(UUID id, String username) {
        return new UserSummaryResponse(id, username, username, null, false);
    }
}
