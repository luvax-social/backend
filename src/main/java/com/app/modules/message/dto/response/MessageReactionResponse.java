package com.app.modules.message.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response for one user's reaction to a message. */
@Schema(description = "A user's emoji reaction to a message")
public record MessageReactionResponse(
        @Schema(description = "The reacting user.") UUID userId,
        @Schema(description = "The single emoji grapheme the user reacted with.") String emoji,
        @Schema(description = "When the user reacted, or last changed the reaction.")
                OffsetDateTime reactedAt) {}
