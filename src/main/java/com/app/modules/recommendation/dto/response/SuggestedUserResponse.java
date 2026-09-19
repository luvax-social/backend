package com.app.modules.recommendation.dto.response;

import com.app.common.response.UserListItemResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One suggested account: the shared list row plus the two fields only this surface needs.
 *
 * <p>{@code bannerUrl} and {@code sources} are deliberately not added to {@link
 * UserSummaryResponse}. That record stays viewer-independent so it remains safe to embed in cached
 * and broadcast payloads, and it is embedded in every post, comment and notification response;
 * widening it would carry two fields across the whole API for the benefit of one card.
 *
 * <p>This record mirrors {@link UserListItemResponse} rather than extending it, because a record
 * cannot extend a record and duplicating two accessors is cheaper than a wrapper.
 */
@Schema(description = "A suggested account with its banner and the reason it is suggested")
public record SuggestedUserResponse(
        @Schema(description = "Public identity summary.") UserSummaryResponse user,
        @Schema(description = "The viewer's relationship to this user.")
                ViewerRelationshipResponse viewerState,
        @Schema(
                        description =
                                "Banner (cover image) CDN URL; null when the account has none",
                        nullable = true)
                String bannerUrl,
        @Schema(
                        description =
                                "Comma-separated labels for why this account is suggested: graph,"
                                        + " gorse, affinity. Null for a cold-start row, which was never"
                                        + " precomputed and therefore has no recorded reason.",
                        example = "affinity,graph",
                        nullable = true)
                String sources,
        @Schema(
                        description =
                                "Follower count, read from the trigger-maintained counter on the"
                                        + " account. Present for every row.",
                        example = "1240")
                int followerCount) {}
