package com.app.modules.post.place.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for creating a place, or finding the one that already has the name")
public record CreatePlaceRequest(
        @Schema(
                        description =
                                "Place name, 2 to 100 characters containing a letter or a digit."
                                        + " Case, accents and spacing never create a duplicate.",
                        example = "Da Nang",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 100)
                String name) {}
