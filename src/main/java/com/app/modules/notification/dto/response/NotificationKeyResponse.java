package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A position in the notification feed: the {@code (activityAt, id)} tuple that orders it.
 *
 * <p>Used for the seen watermark, the "new" boundary, the newest row ({@code head}) and the
 * mark-all-read bound. Two positions compare by {@code activityAt}, then by {@code id}.
 */
@Schema(description = "A position in the notification feed, the tuple that orders it")
public record NotificationKeyResponse(
        @Schema(description = "Feed sort time of the row", requiredMode = REQUIRED)
                OffsetDateTime activityAt,
        @Schema(description = "Row id, breaking ties on equal activityAt", requiredMode = REQUIRED)
                UUID id) {}
