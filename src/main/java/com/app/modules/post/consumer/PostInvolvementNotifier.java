package com.app.modules.post.consumer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostCollaborator;
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

/**
 * Turns the events that involve another account in a post into notifications: a tag, an invitation
 * to co-author, the answer to one, a caption mention, and a scheduled post that could not be
 * published.
 *
 * <p>Every branch acts on the state the rows hold when the event is processed rather than on what
 * the event described. A tag removed, an invitation withdrawn, a mention edited out or a post
 * archived before the event was consumed writes nothing, so a redelivered or reordered event
 * converges on the same notifications. {@code NotificationService} applies the self, block and
 * toggle guards, so none are repeated here.
 */
@Component
public class PostInvolvementNotifier {

    private static final String ENTITY_TYPE = "post";

    private final NotificationService notificationService;
    private final PostRepository postRepository;
    private final PostUserTagRepository postUserTagRepository;
    private final PostCollaboratorRepository postCollaboratorRepository;
    private final PostMentionRepository postMentionRepository;

    public PostInvolvementNotifier(
            NotificationService notificationService,
            PostRepository postRepository,
            PostUserTagRepository postUserTagRepository,
            PostCollaboratorRepository postCollaboratorRepository,
            PostMentionRepository postMentionRepository) {
        this.notificationService = notificationService;
        this.postRepository = postRepository;
        this.postUserTagRepository = postUserTagRepository;
        this.postCollaboratorRepository = postCollaboratorRepository;
        this.postMentionRepository = postMentionRepository;
    }

    /**
     * Handles one event if it is one of the involvement events.
     *
     * @param eventType the versioned event type
     * @param data the event payload
     * @return true when the event type belongs to this notifier, whether or not it wrote anything
     */
    boolean dispatch(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case PostEventTypes.POST_TAG_CREATED_V1 -> tagCreated(data);
            case PostEventTypes.POST_COLLABORATION_INVITED_V1 -> collaborationInvited(data);
            case PostEventTypes.POST_COLLABORATION_ACCEPTED_V1 -> collaborationAccepted(data);
            case PostEventTypes.POST_MENTION_CREATED_V1 -> mentionCreated(data);
            case PostEventTypes.POST_SCHEDULE_FAILED_V1 -> scheduleFailed(data);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void tagCreated(Map<String, Object> data) {
        if (!Boolean.TRUE.equals(data.get("notify"))) {
            return;
        }
        UUID tagId = uuid(data.get("tagId"));
        Optional<PostUserTag> tag = postUserTagRepository.findById(tagId);
        Optional<Post> post = publishedPost(uuid(data.get("postId")));
        if (tag.isEmpty() || post.isEmpty()) {
            return;
        }
        // The current status picks the type: a tag approved before the event was consumed reads as
        // an ordinary tag rather than as a request that no longer waits for anything.
        NotificationType type =
                tag.get().getStatus() == TagStatus.APPROVED
                        ? NotificationType.POST_TAG
                        : NotificationType.POST_TAG_REQUEST;
        notificationService.create(
                post.get().getUserId(),
                tag.get().getTaggedUserId(),
                type,
                ENTITY_TYPE,
                post.get().getId(),
                post.get().getId());
    }

    private void collaborationInvited(Map<String, Object> data) {
        UUID postId = uuid(data.get("postId"));
        UUID inviteeId = uuid(data.get("inviteeId"));
        Optional<Post> post = publishedPost(postId);
        Optional<PostCollaborator> row = postCollaboratorRepository.find(postId, inviteeId);
        if (post.isEmpty()
                || row.isEmpty()
                || row.get().getStatus() != CollaborationStatus.INVITED) {
            return;
        }
        notificationService.create(
                post.get().getUserId(),
                inviteeId,
                NotificationType.POST_COLLABORATION_INVITE,
                ENTITY_TYPE,
                postId,
                postId);
    }

    private void collaborationAccepted(Map<String, Object> data) {
        UUID postId = uuid(data.get("postId"));
        UUID collaboratorId = uuid(data.get("collaboratorId"));
        UUID ownerId = uuid(data.get("ownerId"));
        Optional<PostCollaborator> row = postCollaboratorRepository.find(postId, collaboratorId);
        if (publishedPost(postId).isEmpty()
                || row.isEmpty()
                || row.get().getStatus() != CollaborationStatus.ACCEPTED) {
            return;
        }
        notificationService.create(
                collaboratorId,
                ownerId,
                NotificationType.POST_COLLABORATION_ACCEPTED,
                ENTITY_TYPE,
                postId,
                postId);
    }

    private void mentionCreated(Map<String, Object> data) {
        if (!Boolean.TRUE.equals(data.get("notify"))) {
            return;
        }
        UUID postId = uuid(data.get("postId"));
        UUID mentionedUserId = uuid(data.get("mentionedUserId"));
        Optional<Post> post = publishedPost(postId);
        if (post.isEmpty()
                || !postMentionRepository.existsById(new PostMentionId(postId, mentionedUserId))) {
            return;
        }
        notificationService.create(
                post.get().getUserId(),
                mentionedUserId,
                NotificationType.MENTION_POST,
                ENTITY_TYPE,
                postId,
                postId);
    }

    private void scheduleFailed(Map<String, Object> data) {
        UUID postId = uuid(data.get("postId"));
        Optional<Post> post = postRepository.findByIdAndDeletedAtIsNull(postId);
        // The notice points at the draft, so it is only worth sending while the post is one.
        if (post.isEmpty() || post.get().getStatus() != PostStatus.DRAFT) {
            return;
        }
        notificationService.create(
                NotificationDraft.systemNotice(
                        post.get().getUserId(),
                        NotificationType.SCHEDULED_POST_FAILED,
                        ENTITY_TYPE,
                        postId,
                        postId,
                        String.valueOf(data.get("reason")),
                        null));
    }

    private Optional<Post> publishedPost(UUID postId) {
        return postRepository
                .findByIdAndDeletedAtIsNull(postId)
                .filter(post -> post.getStatus() == PostStatus.PUBLISHED);
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }
}
