package com.app.modules.message.dto.response;

import java.util.UUID;

import com.app.modules.message.enums.MessageType;

import io.swagger.v3.oas.annotations.media.Schema;

/** Server-resolved summary of the message a reply points at, so a client never has to page back. */
@Schema(description = "Preview of the message a reply refers to")
public record ReplyPreviewResponse(
        @Schema(description = "The replied-to message.") UUID messageId,
        @Schema(description = "Sender of the replied-to message; null if unknown or deleted.")
                UUID senderId,
        @Schema(description = "Content kind of the replied-to message; null if it is unknown.")
                MessageType messageType,
        @Schema(
                        description =
                                "Single-line text excerpt; null for a non-text message and for an"
                                        + " unavailable one.",
                        nullable = true)
                String snippet,
        @Schema(
                        description =
                                "True when the replied-to message is no longer shown: its sender"
                                        + " deleted it, an administrator removed it, or it no longer"
                                        + " exists. The reasons are not distinguished.")
                boolean unavailable) {}
