package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The account's feed state: the badge, the two watermarks and the pinned follow-request entry.
 *
 * <p>{@code seen} is the newest position the account has been shown. {@code previous} is the
 * position that bounds the "new" section: it moves to {@code seen} only on the first advance after
 * a pause of the configured session gap, so the section survives reloads within a visit. Both are
 * null for an account that has never opened the feed.
 */
@Schema(description = "Notification feed state: badge, watermarks and pending follow requests")
public record NotificationStateResponse(
        @Schema(requiredMode = REQUIRED) UnseenCountResponse unseen,
        @Schema(description = "Seen watermark; null before the first visit", nullable = true)
                NotificationKeyResponse seen,
        @Schema(
                        description =
                                "Boundary of the new section for this visit; null before the first"
                                        + " completed visit, in which case every row is new",
                        nullable = true)
                NotificationKeyResponse previous,
        @Schema(requiredMode = REQUIRED) FollowRequestSummaryResponse followRequests) {}
