package com.app.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requesting viewer's relationship to another user, embedded wherever a response renders a
 * follow or block control for that user.
 *
 * <p>All eight fields are always present and never null; an anonymous viewer or a self-reference
 * resolves to every field {@code false} rather than the field being omitted, so a client never has
 * to branch on presence. {@code isFollowing} and {@code isFollowRequested} are mutually exclusive:
 * the {@code follows} row for a pair is either {@code accepted} or {@code pending}, never both.
 *
 * <p>Deliberately carries no "this user has blocked the viewer" field. The application implements a
 * stealth block model: a blocked viewer must be unable to distinguish "this account does not exist"
 * from "this account has blocked me" on any surface, and a boolean confirming the latter is exactly
 * the disclosure that model forbids. Every list this record is embedded in must also exclude an
 * account that has blocked the viewer from the result set entirely, rather than including it with
 * this flag set - the row must not appear, not merely be flagged.
 *
 * <p>{@code isMutingPosts}, {@code isMutingStories} and {@code isShowingFewer} are the viewer's own
 * choices about the other account. They are resolved from the viewer's outgoing rows only, so the
 * other account never sees that it was muted or that someone asked to see less of it.
 */
@Schema(description = "The viewer's relationship to another user")
public record ViewerRelationshipResponse(
        @Schema(description = "Viewer has an accepted follow of this user", example = "false")
                boolean isFollowing,
        @Schema(description = "Viewer has a pending follow request to this user", example = "false")
                boolean isFollowRequested,
        @Schema(description = "This user has an accepted follow of the viewer", example = "false")
                boolean isFollowedBy,
        @Schema(description = "Viewer has blocked this user", example = "false") boolean isBlocking,
        @Schema(
                        description =
                                "Viewer has already reported this user. True exactly when a new"
                                        + " report from this viewer against this user would be"
                                        + " rejected as a duplicate, so a client can disable the"
                                        + " report control instead of submitting and handling the"
                                        + " rejection. Resolved and dismissed reports no longer"
                                        + " make this true.",
                        example = "false")
                boolean hasReported,
        @Schema(description = "Viewer has muted this user's posts", example = "false")
                boolean isMutingPosts,
        @Schema(description = "Viewer has muted this user's stories", example = "false")
                boolean isMutingStories,
        @Schema(
                        description = "Viewer asked to see fewer recommendations from this user",
                        example = "false")
                boolean isShowingFewer) {

    /** Shared all-false instance for an anonymous viewer or a self-reference. */
    public static final ViewerRelationshipResponse NONE =
            new ViewerRelationshipResponse(false, false, false, false, false, false, false, false);
}
