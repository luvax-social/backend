package com.app.modules.post.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.entity.PostCollaboratorId;
import com.app.modules.post.entity.PostMentionId;
import com.app.modules.post.entity.PostUserTag;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.TagStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostCollaboratorRepository;
import com.app.modules.post.repository.PostMentionRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserTagRepository;

@ExtendWith(MockitoExtension.class)
class PostInvolvementNotifierTest {

    @Mock private NotificationService notificationService;
    @Mock private PostRepository postRepository;
    @Mock private PostUserTagRepository postUserTagRepository;
    @Mock private PostCollaboratorRepository postCollaboratorRepository;
    @Mock private PostMentionRepository postMentionRepository;

    private PostInvolvementNotifier notifier;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID tagId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notifier =
                new PostInvolvementNotifier(
                        notificationService,
                        postRepository,
                        postUserTagRepository,
                        postCollaboratorRepository,
                        postMentionRepository);
    }

    private void postIs(PostStatus status) {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(
                        Optional.of(
                                Post.builder().id(postId).userId(ownerId).status(status).build()));
    }

    private PostUserTag tag(TagStatus status) {
        return PostUserTag.builder()
                .id(tagId)
                .postId(postId)
                .taggedUserId(otherId)
                .status(status)
                .build();
    }

    private PostCollaborator collaborator(CollaborationStatus status) {
        return PostCollaborator.builder()
                .id(new PostCollaboratorId(postId, otherId))
                .status(status)
                .build();
    }

    @Test
    void dispatch_anEventOfAnotherKind_isNotHandled() {
        assertThat(notifier.dispatch(PostEventTypes.POST_LIKED_V1, Map.of())).isFalse();

        verifyNoInteractions(notificationService);
    }

    @Test
    void tagCreated_approvedTag_notifiesTheTaggedPersonAsATag() {
        postIs(PostStatus.PUBLISHED);
        when(postUserTagRepository.findById(tagId))
                .thenReturn(Optional.of(tag(TagStatus.APPROVED)));

        boolean handled =
                notifier.dispatch(
                        PostEventTypes.POST_TAG_CREATED_V1,
                        Map.of(
                                "postId",
                                postId.toString(),
                                "tagId",
                                tagId.toString(),
                                "notify",
                                true));

        assertThat(handled).isTrue();
        verify(notificationService)
                .create(ownerId, otherId, NotificationType.POST_TAG, "post", postId, postId);
    }

    @Test
    void tagCreated_pendingTag_notifiesAsARequest() {
        postIs(PostStatus.PUBLISHED);
        when(postUserTagRepository.findById(tagId)).thenReturn(Optional.of(tag(TagStatus.PENDING)));

        notifier.dispatch(
                PostEventTypes.POST_TAG_CREATED_V1,
                Map.of("postId", postId.toString(), "tagId", tagId.toString(), "notify", true));

        verify(notificationService)
                .create(
                        ownerId,
                        otherId,
                        NotificationType.POST_TAG_REQUEST,
                        "post",
                        postId,
                        postId);
    }

    @Test
    void tagCreated_approvedBeforeTheEventWasConsumed_readsAsAnOrdinaryTag() {
        postIs(PostStatus.PUBLISHED);
        // The event described a pending tag, but the row already says approved.
        when(postUserTagRepository.findById(tagId))
                .thenReturn(Optional.of(tag(TagStatus.APPROVED)));

        notifier.dispatch(
                PostEventTypes.POST_TAG_CREATED_V1,
                Map.of(
                        "postId",
                        postId.toString(),
                        "tagId",
                        tagId.toString(),
                        "outcome",
                        "pending",
                        "notify",
                        true));

        verify(notificationService)
                .create(ownerId, otherId, NotificationType.POST_TAG, "post", postId, postId);
    }

    @Test
    void tagCreated_notifyFalseTag_writesNothing() {
        notifier.dispatch(
                PostEventTypes.POST_TAG_CREATED_V1,
                Map.of("postId", postId.toString(), "tagId", tagId.toString(), "notify", false));

        verifyNoInteractions(notificationService, postUserTagRepository);
    }

    @Test
    void tagCreated_tagRemovedBeforeTheEventWasConsumed_writesNothing() {
        postIs(PostStatus.PUBLISHED);
        when(postUserTagRepository.findById(tagId)).thenReturn(Optional.empty());

        notifier.dispatch(
                PostEventTypes.POST_TAG_CREATED_V1,
                Map.of("postId", postId.toString(), "tagId", tagId.toString(), "notify", true));

        verifyNoInteractions(notificationService);
    }

    @Test
    void tagCreated_postArchivedBeforeTheEventWasConsumed_writesNothing() {
        postIs(PostStatus.ARCHIVED);
        when(postUserTagRepository.findById(tagId))
                .thenReturn(Optional.of(tag(TagStatus.APPROVED)));

        notifier.dispatch(
                PostEventTypes.POST_TAG_CREATED_V1,
                Map.of("postId", postId.toString(), "tagId", tagId.toString(), "notify", true));

        verifyNoInteractions(notificationService);
    }

    @Test
    void collaborationInvited_openInvitation_notifiesTheInvitee() {
        postIs(PostStatus.PUBLISHED);
        when(postCollaboratorRepository.find(postId, otherId))
                .thenReturn(Optional.of(collaborator(CollaborationStatus.INVITED)));

        notifier.dispatch(
                PostEventTypes.POST_COLLABORATION_INVITED_V1,
                Map.of("postId", postId.toString(), "inviteeId", otherId.toString()));

        verify(notificationService)
                .create(
                        ownerId,
                        otherId,
                        NotificationType.POST_COLLABORATION_INVITE,
                        "post",
                        postId,
                        postId);
    }

    @Test
    void collaborationInvited_withdrawnBeforeTheEventWasConsumed_writesNothing() {
        postIs(PostStatus.PUBLISHED);
        when(postCollaboratorRepository.find(postId, otherId))
                .thenReturn(Optional.of(collaborator(CollaborationStatus.REMOVED)));

        notifier.dispatch(
                PostEventTypes.POST_COLLABORATION_INVITED_V1,
                Map.of("postId", postId.toString(), "inviteeId", otherId.toString()));

        verifyNoInteractions(notificationService);
    }

    @Test
    void collaborationAccepted_notifiesTheOwnerWithTheCollaboratorAsActor() {
        postIs(PostStatus.PUBLISHED);
        when(postCollaboratorRepository.find(postId, otherId))
                .thenReturn(Optional.of(collaborator(CollaborationStatus.ACCEPTED)));

        notifier.dispatch(
                PostEventTypes.POST_COLLABORATION_ACCEPTED_V1,
                Map.of(
                        "postId",
                        postId.toString(),
                        "collaboratorId",
                        otherId.toString(),
                        "ownerId",
                        ownerId.toString()));

        verify(notificationService)
                .create(
                        otherId,
                        ownerId,
                        NotificationType.POST_COLLABORATION_ACCEPTED,
                        "post",
                        postId,
                        postId);
    }

    @Test
    void collaborationAccepted_leftBeforeTheEventWasConsumed_writesNothing() {
        postIs(PostStatus.PUBLISHED);
        when(postCollaboratorRepository.find(postId, otherId))
                .thenReturn(Optional.of(collaborator(CollaborationStatus.LEFT)));

        notifier.dispatch(
                PostEventTypes.POST_COLLABORATION_ACCEPTED_V1,
                Map.of(
                        "postId",
                        postId.toString(),
                        "collaboratorId",
                        otherId.toString(),
                        "ownerId",
                        ownerId.toString()));

        verifyNoInteractions(notificationService);
    }

    @Test
    void mentionCreated_notifiableMentionStillStored_notifiesTheMentionedAccount() {
        postIs(PostStatus.PUBLISHED);
        when(postMentionRepository.existsById(new PostMentionId(postId, otherId))).thenReturn(true);

        notifier.dispatch(
                PostEventTypes.POST_MENTION_CREATED_V1,
                Map.of(
                        "postId",
                        postId.toString(),
                        "mentionedUserId",
                        otherId.toString(),
                        "notify",
                        true));

        verify(notificationService)
                .create(ownerId, otherId, NotificationType.MENTION_POST, "post", postId, postId);
    }

    @Test
    void mentionCreated_notifyFalse_writesNothing() {
        notifier.dispatch(
                PostEventTypes.POST_MENTION_CREATED_V1,
                Map.of(
                        "postId",
                        postId.toString(),
                        "mentionedUserId",
                        otherId.toString(),
                        "notify",
                        false));

        verifyNoInteractions(notificationService, postMentionRepository);
    }

    @Test
    void mentionCreated_mentionEditedOutBeforeTheEventWasConsumed_writesNothing() {
        postIs(PostStatus.PUBLISHED);
        when(postMentionRepository.existsById(new PostMentionId(postId, otherId)))
                .thenReturn(false);

        notifier.dispatch(
                PostEventTypes.POST_MENTION_CREATED_V1,
                Map.of(
                        "postId",
                        postId.toString(),
                        "mentionedUserId",
                        otherId.toString(),
                        "notify",
                        true));

        verifyNoInteractions(notificationService);
    }

    @Test
    void scheduleFailed_postStillADraft_sendsAPlatformNoticeWithTheReason() {
        postIs(PostStatus.DRAFT);

        notifier.dispatch(
                PostEventTypes.POST_SCHEDULE_FAILED_V1,
                Map.of("postId", postId.toString(), "reason", "banned_hashtag"));

        ArgumentCaptor<NotificationDraft> draft = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(notificationService).create(draft.capture());
        assertThat(draft.getValue().actorId()).isNull();
        assertThat(draft.getValue().recipientId()).isEqualTo(ownerId);
        assertThat(draft.getValue().type()).isEqualTo(NotificationType.SCHEDULED_POST_FAILED);
        assertThat(draft.getValue().entityId()).isEqualTo(postId);
        assertThat(draft.getValue().message()).isEqualTo("banned_hashtag");
    }

    @Test
    void scheduleFailed_postPublishedAgainOrDeleted_writesNothing() {
        postIs(PostStatus.PUBLISHED);

        notifier.dispatch(
                PostEventTypes.POST_SCHEDULE_FAILED_V1,
                Map.of("postId", postId.toString(), "reason", "banned_hashtag"));

        verify(notificationService, org.mockito.Mockito.never())
                .create(any(NotificationDraft.class));
    }
}
