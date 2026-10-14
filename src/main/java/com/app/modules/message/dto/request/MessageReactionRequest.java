package com.app.modules.message.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for reacting to a message. The emoji is validated by the service, after the access
 * checks, so a caller who may not see the message learns nothing from the validation answer.
 */
@Schema(description = "Message reaction payload")
public record MessageReactionRequest(
        @Schema(description = "Exactly one emoji grapheme, for example a thumbs up.")
                String emoji) {}
