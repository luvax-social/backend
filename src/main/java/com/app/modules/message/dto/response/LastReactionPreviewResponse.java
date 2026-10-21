package com.app.modules.message.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** The newest visible reaction in a conversation, shown beside its list row. */
@Schema(description = "Newest reaction in a conversation, for the conversation list")
public record LastReactionPreviewResponse(
        @Schema(description = "The reacting user.") UUID userId,
        @Schema(description = "The emoji they reacted with.") String emoji,
        @Schema(description = "The message they reacted to.") UUID messageId,
        @Schema(description = "When they reacted.") OffsetDateTime reactedAt,
        @Schema(description = "Whether the reacted-to message was sent by the caller.")
                boolean onMyMessage) {}
