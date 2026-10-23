package com.app.modules.post.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for setting or clearing the place of a post")
public record SetPostPlaceRequest(
        @Schema(
                        description =
                                "Id of an existing place; null with no placeName clears the place",
                        nullable = true)
                UUID placeId,
        @Schema(
                        description =
                                "Name of a place, created when new; used when placeId is absent",
                        nullable = true)
                @Size(max = 100)
                String placeName) {}
