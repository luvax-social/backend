package com.app.modules.message.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response for a single conversation's detail. */
@Schema(description = "Conversation detail")
public record ConversationResponse(
        @Schema(description = "Conversation identifier.") UUID id,
        @Schema(description = "User who created the conversation.", nullable = true) UUID createdBy,
        @Schema(description = "Active and former members.") List<ParticipantResponse> participants,
        @Schema(
                        description = "Creation time of the newest message; null if none yet.",
                        nullable = true)
                OffsetDateTime lastMessageAt,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(description = "Whether the caller has pinned this conversation.") boolean pinned,
        @Schema(description = "Whether the caller has muted this conversation.") boolean muted,
        @Schema(
                        description =
                                "accepted, or pending while the recipient has not accepted. A"
                                        + " declined request reads as pending to its sender.")
                String requestStatus,
        @Schema(description = "Whether the caller is the one asked to accept this request.")
                boolean isRequestRecipient,
        @Schema(
                        description =
                                "awaiting_acceptance when the caller is the sender of an"
                                        + " unaccepted request and may not send another message;"
                                        + " otherwise none.")
                String sendRestriction) {}
