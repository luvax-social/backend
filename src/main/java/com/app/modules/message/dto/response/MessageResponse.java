package com.app.modules.message.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.modules.message.enums.MessageType;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response for a single message, including a tombstoned one. */
@Schema(description = "A message within a conversation")
public record MessageResponse(
        @Schema(description = "Message identifier.") UUID id,
        @Schema(description = "Conversation the message belongs to.") UUID conversationId,
        @Schema(description = "Sender; null if the sending user's account was deleted.")
                UUID senderId,
        @Schema(description = "Content kind of the message.") MessageType messageType,
        @Schema(
                        description =
                                "Text body; null for a non-text message, a message its sender deleted,"
                                        + " or one an administrator removed.")
                String content,
        @Schema(description = "Referenced media asset for an image/video message.")
                UUID mediaAssetId,
        @Schema(
                        description =
                                "Resolved media for an image or video message; null otherwise."
                                        + " Present so a recipient can render the attachment"
                                        + " without a separate lookup, which the media module does"
                                        + " not offer.",
                        nullable = true)
                MessageMediaResponse media,
        @Schema(description = "Shared post for a post-share message.") UUID sharedPostId,
        @Schema(description = "Shared story for a story-share message.") UUID sharedStoryId,
        @Schema(description = "Message this one replies to, or null.") UUID replyToId,
        @Schema(
                        description =
                                "Server-resolved preview of the replied-to message; null when this"
                                        + " message is not a reply or is itself no longer shown.",
                        nullable = true)
                ReplyPreviewResponse replyTo,
        @Schema(
                        description =
                                "True if this message is no longer shown: either its sender deleted it"
                                        + " or an administrator removed it. The two are not distinguished"
                                        + " on the wire, because both render the same placeholder.")
                boolean isDeleted,
        @Schema(
                        description =
                                "When the message stopped being shown; null unless it is deleted. A"
                                        + " sender deletion wins over a later administrative removal,"
                                        + " because that is the timestamp the participants were already"
                                        + " shown.")
                OffsetDateTime deletedAt,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(
                        description =
                                "Reactions the caller may see, oldest first; empty for a message that"
                                        + " is no longer shown, because a restore brings them back.")
                List<MessageReactionResponse> reactions) {

    public MessageResponse {
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
    }
}
