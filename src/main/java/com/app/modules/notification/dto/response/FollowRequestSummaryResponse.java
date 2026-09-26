package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;

import com.app.common.response.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The pinned "follow requests" entry above the feed: how many requests are pending and who sent the
 * newest ones.
 *
 * <p>Read from the follow graph, not from notification rows, so it is right even for an account
 * that switched follow notifications off. Pending requests never appear as rows in the feed.
 */
@Schema(description = "Pending follow requests summarised for the pinned entry")
public record FollowRequestSummaryResponse(
        @Schema(description = "Pending requests, at most 99", requiredMode = REQUIRED) int count,
        @Schema(description = "True when more than 99 are pending", requiredMode = REQUIRED)
                boolean capped,
        @Schema(description = "Up to two newest requesters, newest first", requiredMode = REQUIRED)
                List<UserSummaryResponse> recent) {

    public static final FollowRequestSummaryResponse NONE =
            new FollowRequestSummaryResponse(0, false, List.of());
}
