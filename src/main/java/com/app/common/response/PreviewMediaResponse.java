package com.app.common.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** The first media item of a post or story, enough to draw a thumbnail. */
@Schema(description = "Thumbnail source of a post or story")
public record PreviewMediaResponse(
        @Schema(description = "Public CDN URL of the media object", requiredMode = REQUIRED)
                String url,
        @Schema(description = "Client-generated blurhash placeholder", nullable = true)
                String blurhash,
        @Schema(description = "image or video", requiredMode = REQUIRED) String mediaType,
        @Schema(description = "Width in pixels", nullable = true) Integer width,
        @Schema(description = "Height in pixels", nullable = true) Integer height) {}
