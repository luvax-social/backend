package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The viewer's relationship to the newest actor of a follow notification, which drives the follow
 * back action and the confirm and delete actions.
 */
@Schema(description = "Viewer's relationship to the actor of a follow notification")
public record NotificationRelationshipResponse(
        @Schema(description = "The viewer follows the actor", requiredMode = REQUIRED)
                boolean isFollowing,
        @Schema(
                        description = "The viewer has a pending request to follow the actor",
                        requiredMode = REQUIRED)
                boolean isRequested,
        @Schema(
                        description = "The actor has a pending request to follow the viewer",
                        requiredMode = REQUIRED)
                boolean hasPendingRequestFrom) {}
