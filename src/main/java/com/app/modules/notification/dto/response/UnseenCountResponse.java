package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The badge: how many notifications arrived since the seen watermark, counted to a cap.
 *
 * <p>The count reads at most {@code CAP + 1} rows, so its cost does not grow with the backlog.
 */
@Schema(description = "Unseen notification count for the badge, bounded")
public record UnseenCountResponse(
        @Schema(
                        description = "Unseen notifications, at most 99",
                        example = "3",
                        requiredMode = REQUIRED)
                int count,
        @Schema(
                        description = "True when more than 99 are unseen; render 99+",
                        requiredMode = REQUIRED)
                boolean capped) {

    /** The largest number the badge shows before it reads 99+. */
    public static final int CAP = 99;

    /** Builds the badge from a count read with a limit of {@code CAP + 1}. */
    public static UnseenCountResponse of(long counted) {
        return new UnseenCountResponse((int) Math.min(counted, CAP), counted > CAP);
    }
}
