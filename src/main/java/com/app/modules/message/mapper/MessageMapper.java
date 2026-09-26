package com.app.modules.message.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.app.modules.media.entity.MediaAsset;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.MessageMediaResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.ParticipantResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.Message;
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
     * @return the conversation detail response
     */
    ConversationResponse toConversationResponse(
            Conversation conversation,
            List<ParticipantResponse> participants,
            boolean pinned,
            boolean muted);

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
     * @return the conversation summary response
     */
    @Mapping(source = "conversation.id", target = "id")
    ConversationSummaryResponse toSummaryResponse(
            Conversation conversation,
            List<ParticipantResponse> participants,
            long unreadCount,
            MessageResponse lastMessage,
            boolean pinned,
            boolean muted,
            boolean manuallyUnread);

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
        if (message == null) {
            return null;
        }
        boolean adminRemoved = message.getAdminRemovedAt() != null;
        OffsetDateTime tombstonedAt =
                message.getDeletedAt() != null
                        ? message.getDeletedAt()
                        : message.getAdminRemovedAt();
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
                message.isDeleted() || adminRemoved,
                tombstonedAt,
                message.getCreatedAt());
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
