package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.entity.PostCollaboratorId;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
class PostVisibilityServiceImplTest {

    @Mock private PostUserRepository postUserRepository;
    @Mock private SocialService socialService;
    @Mock private PostCollaboratorRepository postCollaboratorRepository;

    private PostVisibilityServiceImpl service;

    private final UUID viewerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostVisibilityServiceImpl(
                        postUserRepository, socialService, postCollaboratorRepository);
    }

    private Post post() {
        return Post.builder().id(UUID.randomUUID()).userId(ownerId).build();
    }

    private User owner(boolean isPrivate) {
        return User.builder().id(ownerId).isPrivate(isPrivate).build();
    }

    @Test
    void isVisibleTo_viewerIsOwner_returnsTrue() {
        assertThat(service.isVisibleTo(ownerId, post())).isTrue();
        verifyNoInteractions(socialService, postUserRepository);
    }

    @Test
    void isVisibleTo_publicAccountNoBlock_returnsTrue() {
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(false);
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(owner(false)));

        assertThat(service.isVisibleTo(viewerId, post())).isTrue();
    }

    @Test
    void isVisibleTo_privateAccountWithAcceptedFollow_returnsTrue() {
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(false);
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(owner(true)));
        when(socialService.hasAcceptedFollow(viewerId, ownerId)).thenReturn(true);

        assertThat(service.isVisibleTo(viewerId, post())).isTrue();
    }

    @Test
    void isVisibleTo_privateAccountWithoutFollow_returnsFalse() {
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(false);
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(owner(true)));
        when(socialService.hasAcceptedFollow(viewerId, ownerId)).thenReturn(false);

        assertThat(service.isVisibleTo(viewerId, post())).isFalse();
    }

    @Test
    void isVisibleTo_blockedEitherDirection_returnsFalse() {
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(true);

        assertThat(service.isVisibleTo(viewerId, post())).isFalse();
        verifyNoInteractions(postUserRepository);
    }

    @Test
    void isVisibleTo_ownerSoftDeleted_returnsFalse() {
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(false);
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId)).thenReturn(Optional.empty());

        assertThat(service.isVisibleTo(viewerId, post())).isFalse();
    }

    @Test
    void isVisibleTo_viewerIsAcceptedCoAuthor_returnsTrueWithoutBlockChecks() {
        Post post = post();
        when(postCollaboratorRepository.findAcceptedUserIds(post.getId()))
                .thenReturn(List.of(viewerId));

        assertThat(service.isVisibleTo(viewerId, post)).isTrue();
        verifyNoInteractions(socialService, postUserRepository);
    }

    @Test
    void isVisibleTo_viewerBlockedWithAnAcceptedCoAuthor_returnsFalse() {
        Post post = post();
        UUID coAuthor = UUID.randomUUID();
        when(postCollaboratorRepository.findAcceptedUserIds(post.getId()))
                .thenReturn(List.of(coAuthor));
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(false);
        when(socialService.isBlockedBetween(viewerId, coAuthor)).thenReturn(true);

        assertThat(service.isVisibleTo(viewerId, post)).isFalse();
    }

    @Test
    void isVisibleTo_privateOwnerDecidesTheAudienceOfACoAuthoredPost() {
        Post post = post();
        UUID coAuthor = UUID.randomUUID();
        when(postCollaboratorRepository.findAcceptedUserIds(post.getId()))
                .thenReturn(List.of(coAuthor));
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(owner(true)));
        when(socialService.hasAcceptedFollow(viewerId, ownerId)).thenReturn(false);

        assertThat(service.isVisibleTo(viewerId, post)).isFalse();
    }

    @Test
    void filterVisiblePostIds_viewerBlockedWithACoAuthor_excludesOnlyThatPost() {
        UUID plainPost = UUID.randomUUID();
        UUID sharedPost = UUID.randomUUID();
        UUID coAuthor = UUID.randomUUID();
        when(socialService.findBlockedEitherDirection(viewerId)).thenReturn(Set.of(coAuthor));
        when(socialService.loadRelationships(any(), anyCollection())).thenReturn(Map.of());
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(List.of(owner(false)));
        when(postCollaboratorRepository.findByPostIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                PostCollaborator.builder()
                                        .id(new PostCollaboratorId(sharedPost, coAuthor))
                                        .status(CollaborationStatus.ACCEPTED)
                                        .build()));

        Set<UUID> visible =
                service.filterVisiblePostIds(
                        viewerId, Map.of(plainPost, ownerId, sharedPost, ownerId));

        assertThat(visible).containsExactly(plainPost);
    }

    @Test
    void filterVisiblePostIds_pendingInvitationDoesNotHideAPost() {
        UUID sharedPost = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        when(socialService.findBlockedEitherDirection(viewerId)).thenReturn(Set.of(invitee));
        when(socialService.loadRelationships(any(), anyCollection())).thenReturn(Map.of());
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(List.of(owner(false)));
        when(postCollaboratorRepository.findByPostIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                PostCollaborator.builder()
                                        .id(new PostCollaboratorId(sharedPost, invitee))
                                        .status(CollaborationStatus.INVITED)
                                        .build()));

        assertThat(service.filterVisiblePostIds(viewerId, Map.of(sharedPost, ownerId)))
                .containsExactly(sharedPost);
    }

    @Test
    void filterVisiblePostIds_viewerIsACoAuthor_alwaysSeesThePost() {
        UUID sharedPost = UUID.randomUUID();
        when(socialService.findBlockedEitherDirection(viewerId)).thenReturn(Set.of(ownerId));
        when(socialService.loadRelationships(any(), anyCollection())).thenReturn(Map.of());
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(List.of(owner(false)));
        when(postCollaboratorRepository.findByPostIdIn(anyCollection()))
                .thenReturn(
                        List.of(
                                PostCollaborator.builder()
                                        .id(new PostCollaboratorId(sharedPost, viewerId))
                                        .status(CollaborationStatus.ACCEPTED)
                                        .build()));

        assertThat(service.filterVisiblePostIds(viewerId, Map.of(sharedPost, ownerId)))
                .containsExactly(sharedPost);
    }

    @Test
    void filterVisibleOwnerIds_emptyInput_returnsEmptyWithoutQuerying() {
        assertThat(service.filterVisibleOwnerIds(viewerId, Set.of())).isEmpty();
        verifyNoInteractions(socialService, postUserRepository);
    }

    @Test
    void filterVisibleOwnerIds_issuesExactlyThreeBatchedQueriesRegardlessOfCandidateCount() {
        UUID publicOwner = UUID.randomUUID();
        UUID privateFollowed = UUID.randomUUID();
        UUID privateNotFollowed = UUID.randomUUID();
        UUID blockedEitherDirection = UUID.randomUUID();
        UUID softDeleted = UUID.randomUUID();
        Set<UUID> candidates =
                Set.of(
                        viewerId,
                        publicOwner,
                        privateFollowed,
                        privateNotFollowed,
                        blockedEitherDirection,
                        softDeleted);

        when(socialService.findBlockedEitherDirection(viewerId))
                .thenReturn(Set.of(blockedEitherDirection));
        when(socialService.loadRelationships(any(), anyCollection()))
                .thenReturn(
                        Map.of(
                                privateFollowed,
                                new ViewerRelationshipResponse(
                                        true, false, false, false, false, false, false, false),
                                privateNotFollowed,
                                new ViewerRelationshipResponse(
                                        false, false, false, false, false, false, false, false)));
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(candidates))
                .thenReturn(
                        List.of(
                                User.builder().id(publicOwner).isPrivate(false).build(),
                                User.builder().id(privateFollowed).isPrivate(true).build(),
                                User.builder().id(privateNotFollowed).isPrivate(true).build()));

        Set<UUID> visible = service.filterVisibleOwnerIds(viewerId, candidates);

        assertThat(visible).containsExactlyInAnyOrder(viewerId, publicOwner, privateFollowed);
        verify(socialService).findBlockedEitherDirection(viewerId);
        verify(socialService).loadRelationships(viewerId, candidates);
        verify(postUserRepository).findAllByIdInAndDeletedAtIsNull(candidates);
        verify(socialService, never()).isBlockedBetween(any(), any());
        verify(socialService, never()).hasAcceptedFollow(any(), any());
        verify(postUserRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    void filterVisibleOwnerIds_blockedInEitherDirection_excluded() {
        UUID blocked = UUID.randomUUID();
        when(socialService.findBlockedEitherDirection(viewerId)).thenReturn(Set.of(blocked));
        when(postUserRepository.findAllByIdInAndDeletedAtIsNull(Set.of(blocked)))
                .thenReturn(List.of(User.builder().id(blocked).isPrivate(false).build()));

        Set<UUID> visible = service.filterVisibleOwnerIds(viewerId, Set.of(blocked));

        assertThat(visible).isEmpty();
    }
}
