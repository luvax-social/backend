package com.app.modules.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One selectable verification category.
 *
 * <p>{@code iconKey} is an opaque token to the server. The client maps it to a glyph, which is what
 * lets the badge be redrawn without a schema change.
 */
@Schema(description = "A verification category a requester can choose")
public record VerificationCategoryResponse(
        @Schema(description = "Stable key", example = "music") String categoryKey,
        @Schema(description = "Label to render", example = "Music") String displayName,
        @Schema(description = "Occupations this category covers") String covers,
        @Schema(description = "Glyph key the client maps to a component", example = "music-note")
                String iconKey) {}
