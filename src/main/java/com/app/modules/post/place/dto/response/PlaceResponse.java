package com.app.modules.post.place.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response for a usable place: its name and how many published posts use it. */
@Schema(description = "A named place a post can be tagged at")
public record PlaceResponse(
        @Schema(description = "Place identifier.") UUID id,
        @Schema(description = "The place name as first written.") String name,
        @Schema(description = "Number of published posts at this place; trigger-maintained.")
                int postCount) {}
