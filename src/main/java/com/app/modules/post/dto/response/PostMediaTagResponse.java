package com.app.modules.post.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.post.enums.TagStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** A person tagged on one media item, as the viewer is allowed to see the tag. */
@Schema(description = "A tag on a media item")
public record PostMediaTagResponse(
        @Schema(description = "Tag identifier.") UUID id,
        @Schema(description = "The tagged person.") UserSummaryResponse user,
        @Schema(description = "Horizontal position as a fraction of the item, 0 to 1.")
                BigDecimal x,
        @Schema(description = "Vertical position as a fraction of the item, 0 to 1.") BigDecimal y,
        @Schema(
                        description =
                                "An approved tag shows to everyone who can see the post; a pending"
                                        + " tag is returned only to the post owner and the tagged"
                                        + " person.")
                TagStatus status) {}
