package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a notification opens.
 *
 * <p>{@code kind} decides which ids are set: {@code post} sets {@code postId}; {@code comment} sets
 * {@code commentId} and {@code postId}; {@code story} sets {@code storyId}; {@code user} sets
 * {@code userId}; {@code support_ticket} sets {@code ticketId}; {@code moderation} sets none (the
 * notice itself is the content; its {@code moderation} block carries the appeal route); {@code
 * none} is a row with nothing to open.
 */
@Schema(description = "What a notification opens")
public record NotificationTargetResponse(
        @Schema(
                        description =
                                "post, comment, story, user, support_ticket, moderation or none",
                        example = "comment",
                        requiredMode = REQUIRED)
                String kind,
        @Schema(nullable = true) UUID postId,
        @Schema(nullable = true) UUID commentId,
        @Schema(nullable = true) UUID storyId,
        @Schema(nullable = true) UUID ticketId,
        @Schema(nullable = true) UUID userId,
        @Schema(
                        description =
                                "False when the target was deleted, removed, expired or is hidden"
                                        + " from the viewer; render muted and do not navigate",
                        requiredMode = REQUIRED)
                boolean available) {}
