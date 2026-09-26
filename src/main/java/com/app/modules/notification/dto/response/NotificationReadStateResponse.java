package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** A notification's read state after a mark-read or mark-unread. */
@Schema(description = "A notification's read state")
public record NotificationReadStateResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(description = "When it was read; null when unread", nullable = true)
                OffsetDateTime readAt) {}
