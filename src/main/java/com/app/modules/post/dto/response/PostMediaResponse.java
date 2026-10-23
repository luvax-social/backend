package com.app.modules.post.dto.response;

import java.util.List;
import java.util.UUID;

import com.app.modules.media.enums.MediaType;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response for a single ordered media item of a post, hydrated from {@code media_assets}. */
@Schema(description = "Ordered media item of a post with rendering metadata")
public record PostMediaResponse(
        @Schema(description = "Post media row identifier.") UUID id,
        @Schema(description = "Referenced media asset identifier.") UUID mediaAssetId,
        @Schema(description = "Zero-based carousel position.") short position,
        @Schema(
                        description =
                                "Alt text the author wrote for this item; null when none, and"
                                        + " readers then get a label the client generates.",
                        nullable = true)
                String altText,
        @Schema(description = "Public CDN URL of the media object.") String cdnUrl,
        @Schema(description = "Media category.") MediaType mediaType,
        @Schema(description = "Media width in pixels.", nullable = true) Integer width,
        @Schema(description = "Media height in pixels.", nullable = true) Integer height,
        @Schema(description = "Client-generated blurhash preview.", nullable = true)
                String blurhash,
        @Schema(
                        description =
                                "People tagged on this item, as the viewer may see them. Pending"
                                        + " tags appear only for the post owner and the tagged"
                                        + " person.")
                List<PostMediaTagResponse> tags) {}
