package com.app.modules.post.place.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** The place line of a post: enough to draw the label and link to the location page. */
@Schema(description = "The place a post is tagged at")
public record PlaceSummaryResponse(
        @Schema(description = "Place identifier.") UUID id,
        @Schema(description = "The place name.") String name) {}
