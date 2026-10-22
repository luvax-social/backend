package com.app.modules.post.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.response.PreviewMediaResponse;
import com.app.common.response.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/** A tag waiting for the viewer's approval. */
@Schema(description = "A pending tag of the viewer on someone's post")
public record TagRequestResponse(
        @Schema(description = "Tag identifier.") UUID tagId,
        @Schema(description = "The post the viewer is tagged in.") UUID postId,
        @Schema(description = "The post owner who tagged the viewer.", nullable = true)
                UserSummaryResponse requestedBy,
        @Schema(description = "First media item of the post, for a thumbnail.", nullable = true)
                PreviewMediaResponse media,
        @Schema(description = "When the tag was placed.") OffsetDateTime createdAt) {}
