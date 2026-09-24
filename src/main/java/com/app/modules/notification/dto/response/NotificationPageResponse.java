package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;

import com.app.common.response.CursorPageResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A page of the activity feed: the cursor page shape every list in the API uses, plus {@code head}.
 *
 * <p>{@code head} is the newest visible notification across every type, pending follow requests
 * included, set on the first page only. A pending request is never a row of the feed; it is
 * rendered by the pinned follow-request entry above it. The client therefore advances the seen
 * watermark with {@code head} after rendering the first page, not with the first row, so a request
 * it has shown in the pinned entry stops counting as unseen.
 */
@Schema(description = "A page of the activity feed")
public record NotificationPageResponse(
        @Schema(requiredMode = REQUIRED) List<NotificationItemResponse> content,
        @Schema(requiredMode = REQUIRED) CursorPageResponse.PageInfo pageInfo,
        @Schema(description = "Always false for this feed", requiredMode = REQUIRED)
                boolean degraded,
        @Schema(
                        description =
                                "Newest visible notification, pending follow requests included; set"
                                        + " on the first page only, null on later pages or when the"
                                        + " feed is empty",
                        nullable = true)
                NotificationKeyResponse head) {}
