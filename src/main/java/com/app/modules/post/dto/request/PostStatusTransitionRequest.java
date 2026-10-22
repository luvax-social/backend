package com.app.modules.post.dto.request;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

import com.app.modules.post.enums.PostStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for transitioning a post lifecycle status")
public record PostStatusTransitionRequest(
        @Schema(
                        description =
                                "Target status; valid transitions are draft to published, draft to"
                                        + " scheduled, scheduled to draft, scheduled to scheduled"
                                        + " (reschedule), scheduled to published, published to"
                                        + " archived, archived to published, and any status to"
                                        + " removed",
                        example = "archived",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                PostStatus targetStatus,
        @Schema(
                        description =
                                "When the post is published; required when the target is scheduled"
                                        + " and refused otherwise. Floored to the minute and between"
                                        + " one minute and thirty days from now.",
                        nullable = true)
                OffsetDateTime scheduledAt) {}
