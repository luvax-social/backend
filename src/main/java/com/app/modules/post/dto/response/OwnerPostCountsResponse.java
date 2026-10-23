package com.app.modules.post.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many unpublished posts the owner holds in each state, and the cap that applies. */
@Schema(description = "The owner's drafts, scheduled posts and archived posts")
public record OwnerPostCountsResponse(
        @Schema(description = "Drafts.") long draft,
        @Schema(description = "Scheduled posts.") long scheduled,
        @Schema(description = "Archived posts.") long archived,
        @Schema(
                        description =
                                "The most drafts and scheduled posts one account can hold"
                                        + " together.")
                long limit) {}
