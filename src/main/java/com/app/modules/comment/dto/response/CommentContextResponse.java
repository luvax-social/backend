package com.app.modules.comment.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A comment with every ancestor above it, so a deep link can show a reply in its thread even when
 * it sits on a later page or deep in a reply chain.
 *
 * @param thread the top-level comment first, then each reply down to and including the target
 */
@Schema(description = "A comment and its ancestors, top-level first")
public record CommentContextResponse(
        @Schema(description = "The post the thread belongs to", requiredMode = REQUIRED)
                UUID postId,
        @Schema(
                        description =
                                "Top-level comment first, then each reply down to and including the"
                                        + " requested comment, which is always last",
                        requiredMode = REQUIRED)
                List<CommentResponse> thread) {}
