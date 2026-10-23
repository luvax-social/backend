package com.app.modules.post.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.app.modules.post.enums.PostType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Replaces the whole content of a draft or scheduled post.
 *
 * <p>It is a replacement, not a patch: a field left out clears what the post carried, so a client
 * sends the full composer state. The status and the scheduled time are not part of it; they change
 * through the status endpoint.
 */
@Schema(description = "Payload for replacing the content of a draft or scheduled post")
public record UpdatePostContentRequest(
        @Schema(description = "Post caption (max 2200 characters)") @Size(max = 2200)
                String caption,
        @Schema(
                        description = "Post type",
                        example = "image",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                PostType postType,
        @Schema(description = "Legacy form of media; send either this or media, never both.")
                @Size(max = 10)
                List<UUID> mediaIds,
        @Schema(description = "Media items in carousel order, each with its alt text")
                @Size(max = 10)
                @Valid
                List<PostMediaInput> media,
        @Schema(description = "Id of an existing place to tag the post at", nullable = true)
                UUID placeId,
        @Schema(
                        description = "Name of a place to tag the post at, created when new",
                        nullable = true)
                @Size(max = 100)
                String placeName,
        @Schema(description = "People tagged on the media items", nullable = true)
                @Size(max = 200)
                @Valid
                List<PostTagInput> tags,
        @Schema(
                        description = "Accounts invited to co-author the post, at most three",
                        nullable = true)
                @Size(max = 3)
                List<UUID> collaboratorIds) {}
