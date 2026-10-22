package com.app.modules.post.dto.request;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for changing the alt text of one media item of a post")
public record UpdateAltTextRequest(
        @Schema(
                        description =
                                "The new alt text, at most 500 characters. Null or blank clears it,"
                                        + " and readers then get a generated label.",
                        nullable = true)
                @Size(max = 500)
                String altText) {}
