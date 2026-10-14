package com.app.modules.message.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.app.common.text.Snippets;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.MessageMediaResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.ParticipantResponse;
import com.app.modules.message.dto.response.ReplyPreviewResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.Message;
import com.app.modules.message.enums.MessageType;
import com.app.modules.users.entity.User;

/** Maps conversation and participant entities to API response DTOs. */
@Mapper(componentModel = "spring")
public interface MessageMapper {

    /**
     * Projects a membership row and its hydrated user onto the participant summary shape.
     *
     * @param participant the membership row
     * @param user the participating user, separately hydrated; display fields are null if the
     *     lookup missed
     * @return the participant summary
     */
    @Mapping(source = "participant.id.userId", target = "userId")
    @Mapping(source = "user.username", target = "username")
    @Mapping(source = "user.verified", target = "isVerified")
    @Mapping(source = "user.verifiedCategory", target = "verifiedCategory")
    @Mapping(source = "user.displayName", target = "displayName")
    @Mapping(source = "user.avatarUrl", target = "avatarUrl")
    @Mapping(source = "participant.nickname", target = "nickname")
    ParticipantResponse toParticipantResponse(ConversationParticipant participant, User user);

    /**
     * Builds the conversation detail response from the entity, its already-assembled participant
     * summaries, and the caller's own pin/mute state.
     *
     * @param conversation the source conversation
     * @param participants active and former members, already hydrated
     * @param pinned whether the requesting caller has pinned this conversation
     * @param muted whether the requesting caller has muted this conversation
     * @param requestStatusLabel the request status as the caller may see it
     * @param recipientOfRequest whether the caller is the one asked to accept the request
     * @param sendRestrictionLabel whether the caller may not send another message yet
     * @return the conversation detail response
     */
    @Mapping(source = "requestStatusLabel", target = "requestStatus")
    @Mapping(source = "recipientOfRequest", target = "isRequestRecipient")
    @Mapping(source = "sendRestrictionLabel", target = "sendRestriction")
    ConversationResponse toConversationResponse(
            Conversation conversation,
            List<ParticipantResponse> participants,
            boolean pinned,
            boolean muted,
            String requestStatusLabel,
            boolean recipientOfRequest,
            String sendRestrictionLabel);

    /**
     * Builds one conversation-list row from the entity, its active participants, a
     * separately-computed unread count, its newest message preview, and the caller's own pin/mute
     * state.
     *
     * @param conversation the source conversation
     * @param participants active members, already hydrated
     * @param unreadCount unread-message count for the requesting user, computed by the caller
     * @param lastMessage the conversation's newest message, or null if none yet
     * @param pinned whether the requesting caller has pinned this conversation
     * @param muted whether the requesting caller has muted this conversation
     * @param manuallyUnread whether the requesting caller manually flagged this conversation unread
     * @param requestStatusLabel the request status as the caller may see it
     * @param recipientOfRequest whether the caller is the one asked to accept the request
     * @param hiddenRequest whether the caller, as recipient, has the request filed as hidden
     * @param sendRestrictionLabel whether the caller may not send another message yet
     * @return the conversation summary response
     */
    @Mapping(source = "conversation.id", target = "id")
    @Mapping(source = "requestStatusLabel", target = "requestStatus")
    @Mapping(source = "recipientOfRequest", target = "isRequestRecipient")
    @Mapping(source = "hiddenRequest", target = "requestHidden")
    @Mapping(source = "sendRestrictionLabel", target = "sendRestriction")
    ConversationSummaryResponse toSummaryResponse(
            Conversation conversation,
            List<ParticipantResponse> participants,
            long unreadCount,
            MessageResponse lastMessage,
            boolean pinned,
            boolean muted,
            boolean manuallyUnread,
            String requestStatusLabel,
            boolean recipientOfRequest,
            boolean hiddenRequest,
            String sendRestrictionLabel);

    /**
     * Projects a message entity onto its API response shape, including a tombstoned one.
     *
     * @param message the source message
     * @return the message response
     */
    default MessageResponse toMessageResponse(Message message) {
        return toMessageResponse(message, null);
    }

    /**
     * Projects a message together with its already-resolved media.
     *
     * <p>The asset is passed in rather than looked up here so a page of messages costs one batched
     * query instead of one per row.
     *
     * <p>Hand-written rather than generated because a message carries two independent tombstones
     * and the response collapses them into one pair of fields. Either tombstone marks the message
     * deleted; the sender's timestamp wins when both are set, because that is the one the
     * participants already saw.
     *
     * <p>An administrative removal additionally withholds every payload the message carries, not
     * only its text. A reported image is the usual case, and a removal that suppressed the caption
     * while still serving the picture would not be a removal. The row keeps all of it so a restore
     * can return it.
     *
     * @param message the source message
     * @param media the resolved attachment, or null when the message carries none
     * @return the message response
     */
    default MessageResponse toMessageResponse(Message message, MessageMediaResponse media) {
        return toMessageResponse(message, media, null);
    }

    /**
     * Projects a message with its resolved media and the parents its reply preview reads.
     *
     * <p>The parents arrive as one batched lookup per page, tombstoned rows included, so the
     * preview of a reply to a deleted or removed message can say so instead of vanishing. A null
     * map means the caller did not resolve parents and the preview is left out.
     *
     * @param message the source message
     * @param media the resolved attachment, or null when the message carries none
     * @param replyParents the parents of the rendered messages keyed by id, or null
     * @return the message response
     */
    default MessageResponse toMessageResponse(
            Message message, MessageMediaResponse media, Map<UUID, Message> replyParents) {
        if (message == null) {
            return null;
        }
        boolean adminRemoved = message.getAdminRemovedAt() != null;
        boolean tombstoned = message.isDeleted() || adminRemoved;
        OffsetDateTime tombstonedAt =
                message.getDeletedAt() != null
                        ? message.getDeletedAt()
                        : message.getAdminRemovedAt();
        // A message that is itself no longer shown carries no preview: the placeholder it renders
        // as has nothing to quote.
        ReplyPreviewResponse replyTo =
                message.getReplyToId() == null || replyParents == null || tombstoned
                        ? null
                        : toReplyPreview(
                                message.getReplyToId(), replyParents.get(message.getReplyToId()));
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderId(),
                message.getMessageType(),
                adminRemoved ? null : message.getContent(),
                adminRemoved ? null : message.getMediaAssetId(),
                adminRemoved ? null : media,
                adminRemoved ? null : message.getSharedPostId(),
                adminRemoved ? null : message.getSharedStoryId(),
                message.getReplyToId(),
                replyTo,
                tombstoned,
                tombstonedAt,
                message.getCreatedAt());
    }

    /**
     * Builds the preview of a replied-to message.
     *
     * <p>Either tombstone makes the parent unavailable and withholds its text; a restore clears the
     * moderation tombstone, so the next read shows the snippet again. Only a text parent has a
     * snippet, because an image or a share has no text to quote.
     *
     * @param replyToId the id the reply points at
     * @param parent the parent row, or null when it no longer exists
     * @return the preview
     */
    default ReplyPreviewResponse toReplyPreview(UUID replyToId, Message parent) {
        if (parent == null) {
            return new ReplyPreviewResponse(replyToId, null, null, null, true);
        }
        boolean unavailable = parent.isDeleted() || parent.getAdminRemovedAt() != null;
        String snippet =
                unavailable || parent.getMessageType() != MessageType.TEXT
                        ? null
                        : Snippets.of(parent.getContent());
        return new ReplyPreviewResponse(
                parent.getId(),
                parent.getSenderId(),
                parent.getMessageType(),
                snippet,
                unavailable);
    }

    /**
     * Projects a media asset onto the subset a message needs.
     *
     * @param asset the resolved asset, or null
     * @return the response, or null when no asset was supplied
     */
    default MessageMediaResponse toMediaResponse(MediaAsset asset) {
        if (asset == null) {
            return null;
        }
        return new MessageMediaResponse(
                asset.getId(),
                asset.getMediaType(),
                asset.getCdnUrl(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getDuration(),
                asset.getBlurhash());
    }
}
