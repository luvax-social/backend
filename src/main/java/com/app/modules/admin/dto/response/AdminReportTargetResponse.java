package com.app.modules.admin.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.modules.report.enums.ReportType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The reported entity, rendered for moderation review.
 *
 * <p>One flat shape across all five report types rather than five, because a moderator reviewing a
 * queue reads the same three things every time: who made it, what it says, and what it shows.
 * Fields that do not apply to a type are null, and {@code reportType} says which those are.
 *
 * <p>Deliberately not the public {@code PostResponse}. That carries viewer state, which is
 * meaningless here: whether the reviewing moderator has liked or saved the content is not part of
 * the decision, and computing it would imply the moderator is a viewer of the content in the
 * ordinary sense, which is exactly what this endpoint is careful not to make them.
 *
 * @param reportType which kind of entity this is
 * @param entityId the entity's identifier
 * @param ownerId the account that authored it, or null once that account is deleted
 * @param ownerUsername the author's username, or null once that account is deleted
 * @param status the entity's own status where it has one: post status or account status; null for a
 *     comment, story or message, none of which carry one
 * @param text the caption, comment body, message body or profile bio, whichever applies
 * @param mediaUrls CDN URLs of the attached media, in carousel order; empty when there is none
 * @param removed whether the entity is already soft-deleted, so a moderator does not act twice
 * @param createdAt when the entity was created
 * @param emoji the emoji of a reported message reaction, whose {@code ownerId} is the reactor and
 *     whose {@code text} is a snippet of the message it sits on; null for every other type
 */
@Schema(description = "A reported entity rendered for moderation review")
public record AdminReportTargetResponse(
        @Schema(description = "Which kind of entity this is") ReportType reportType,
        @Schema(description = "The entity's identifier") UUID entityId,
        @Schema(
                        description =
                                "The account that authored it; null once that account is deleted",
                        nullable = true)
                UUID ownerId,
        @Schema(
                        description = "The author's username; null once that account is deleted",
                        nullable = true)
                String ownerUsername,
        @Schema(
                        description =
                                "The entity's own status where it has one: post status or account"
                                        + " status. Null for a comment, story or message, none of"
                                        + " which carry one; reportType says which those are.",
                        example = "published",
                        nullable = true)
                String status,
        @Schema(
                        description =
                                "The caption, comment body, message body or profile bio, whichever"
                                        + " applies; null when the entity carries no text",
                        nullable = true)
                String text,
        @Schema(
                        description =
                                "CDN URLs of the attached media, in carousel order; empty when"
                                        + " there is none")
                List<String> mediaUrls,
        @Schema(description = "Whether the entity is already soft-deleted") boolean removed,
        @Schema(description = "When the entity was created") OffsetDateTime createdAt,
        @Schema(
                        description =
                                "The emoji of a reported message reaction; null for every other"
                                        + " type",
                        nullable = true)
                String emoji) {}
