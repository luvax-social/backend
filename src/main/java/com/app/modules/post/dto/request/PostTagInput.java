package com.app.modules.post.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A person tagged on one media item, as the composer sends it before the item has an id.
 *
 * <p>The item is named by its position in the media list of the same request; the response carries
 * the stable {@code post_media} id the tag is stored against.
 */
@Schema(description = "A person tagged at a point on one media item of a post")
public record PostTagInput(
        @Schema(
                        description = "Zero-based position of the media item the tag is on",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @Min(0)
                Integer mediaPosition,
        @Schema(description = "The person to tag", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                UUID userId,
        @Schema(
                        description = "Horizontal position as a fraction of the item, 0 to 1",
                        example = "0.42",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @DecimalMin("0")
                @DecimalMax("1")
                BigDecimal x,
        @Schema(
                        description = "Vertical position as a fraction of the item, 0 to 1",
                        example = "0.61",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @DecimalMin("0")
                @DecimalMax("1")
                BigDecimal y) {}
