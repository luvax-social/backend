package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the activity feed: a single event or an aggregated group, with everything needed to
 * draw it resolved at read time.
 *
 * <p>{@code actors} holds up to two visible actors, newest first; {@code actorCount} counts every
 * visible actor, so "anna, ben and 12 others" is {@code actors} plus {@code actorCount - 2}.
 * Accounts blocked either way, deleted or no longer active are never shown or counted. Platform
 * notices have no actors. {@code moderation} is set only for moderation notices and {@code
 * relationship} only for follow notifications.
 */
@Schema(description = "One row of the activity feed")
public record NotificationItemResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(example = "like_post", requiredMode = REQUIRED) NotificationType type,
        @Schema(example = "like", requiredMode = REQUIRED) NotificationCategory category,
        @Schema(description = "Up to two visible actors, newest first", requiredMode = REQUIRED)
                List<UserSummaryResponse> actors,
        @Schema(description = "Every visible actor", example = "14", requiredMode = REQUIRED)
                int actorCount,
        @Schema(requiredMode = REQUIRED) boolean isRead,
        @Schema(nullable = true) OffsetDateTime readAt,
        @Schema(
                        description =
                                "Above the boundary of the new section for this visit, decided by"
                                        + " the server",
                        requiredMode = REQUIRED)
                boolean isNew,
        @Schema(
                        description = "Feed sort time; moves forward when a group gains an actor",
                        requiredMode = REQUIRED)
                OffsetDateTime activityAt,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
        @Schema(requiredMode = REQUIRED) NotificationTargetResponse target,
        @Schema(description = "Null when the target is unavailable", nullable = true)
                NotificationPreviewResponse preview,
        @Schema(nullable = true) NotificationModerationResponse moderation,
        @Schema(nullable = true) NotificationRelationshipResponse relationship) {}
