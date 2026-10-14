package com.app.modules.message.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response for one row of the caller's conversation list. */
@Schema(description = "Conversation list entry")
public record ConversationSummaryResponse(
        @Schema(description = "Conversation identifier.") UUID id,
        @Schema(description = "Active members.") List<ParticipantResponse> participants,
        @Schema(description = "Number of unread messages for the caller.") long unreadCount,
        @Schema(
                        description = "Creation time of the newest message; null if none yet.",
                        nullable = true)
                OffsetDateTime lastMessageAt,
        @Schema(
                        description =
                                "Preview of the newest message, including a tombstoned one; null"
                                        + " if the conversation has no message yet.",
                        nullable = true)
                MessageResponse lastMessage,
        @Schema(description = "Whether the caller has pinned this conversation.") boolean pinned,
        @Schema(description = "Whether the caller has muted this conversation.") boolean muted,
        @Schema(
                        description =
                                "Whether the caller manually flagged this conversation unread,"
                                        + " independent of unreadCount.")
                boolean manuallyUnread,
        @Schema(
                        description =
                                "accepted, or pending while the recipient has not accepted. A"
                                        + " declined request reads as pending to its sender.")
                String requestStatus,
        @Schema(description = "Whether the caller is the one asked to accept this request.")
                boolean isRequestRecipient,
        @Schema(
                        description =
                                "Whether a pending request is filed in the hidden requests folder;"
                                        + " always false for the sender.")
                boolean requestHidden,
        @Schema(
                        description =
                                "awaiting_acceptance when the caller is the sender of an"
                                        + " unaccepted request and may not send another message;"
                                        + " otherwise none.")
                String sendRestriction,
        @Schema(
                        description =
                                "Newest reaction the caller may see, present only when it is newer"
                                        + " than the last message; it never changes the row order or the"
                                        + " unread count.",
                        nullable = true)
                LastReactionPreviewResponse lastReaction) {}
