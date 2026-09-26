package com.app.modules.hashtag.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** A trending hashtag with one cover image, for the in-feed trending card. */
@Schema(description = "A trending hashtag paired with a cover image from its top post")
public record TrendingPreviewResponse(
        @Schema(description = "Unique hashtag identifier") UUID hashtagId,
        @Schema(description = "Hashtag name without the # prefix", example = "analogue")
                String name,
        @Schema(
                        description =
                                "Posts tagged during the trending window. Null means this hashtag"
                                        + " has no count for this window, which is not zero and not a"
                                        + " withheld figure; render it as new rather than substituting"
                                        + " a lifetime total, which is a different measurement.",
                        example = "2400",
                        nullable = true)
                Integer postCount,
        @Schema(description = "Whether an administrator has pinned this hashtag platform-wide")
                boolean pinned,
        @Schema(
                        description =
                                "CDN url of the cover image of the tag's newest published post."
                                        + " Null when that post carries no renderable media, or when"
                                        + " every candidate post belongs to a private account; the tag"
                                        + " is still returned and the client draws its own fallback.",
                        nullable = true)
                String previewUrl) {}
