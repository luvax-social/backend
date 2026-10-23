package com.app.modules.post.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** One media item of a post as the composer sends it: the uploaded asset and its alt text. */
@Schema(description = "A media asset attached to a post, with its alt text")
public record PostMediaInput(
        @Schema(
                        description = "Media asset id owned by the author",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                UUID mediaId,
        @Schema(
                        description =
                                "Alt text for this item, at most 500 characters. Blank is stored as"
                                        + " none, and readers then get a generated label.",
                        nullable = true)
                @Size(max = 500)
                String altText) {}
