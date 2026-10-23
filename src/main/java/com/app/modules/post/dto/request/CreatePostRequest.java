package com.app.modules.post.dto.request;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for creating a new post")
public record CreatePostRequest(
        @Schema(
                        description =
                                "Post caption (max 2200 characters); #tokens are extracted as"
                                        + " hashtags at publish time and @names are stored as"
                                        + " mentions",
                        example = "Sunset at the beach #sunset #beach")
                @Size(max = 2200)
                String caption,
        @Schema(
                        description = "Post type",
                        example = "image",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                PostType postType,
        @Schema(
                        description =
                                "Legacy form of media: asset ids owned by the author, in carousel"
                                        + " order, with no alt text. Send either this or media,"
                                        + " never both.",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                @Size(max = 10)
                List<UUID> mediaIds,
        @Schema(
                        description =
                                "Media items in carousel order, each with its alt text. Required for"
                                        + " image, video, and carousel posts; must be null or empty"
                                        + " for text posts. Send either this or mediaIds.",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                @Size(max = 10)
                @Valid
                List<PostMediaInput> media,
        @Schema(
                        description =
                                "Initial status: draft, scheduled or published. The default is"
                                        + " published.",
                        example = "published")
                PostStatus status,
        @Schema(
                        description =
                                "When a scheduled post is published. Required for scheduled and"
                                        + " refused for any other status. Floored to the minute and"
                                        + " between one minute and thirty days from now.",
                        nullable = true)
                OffsetDateTime scheduledAt,
        @Schema(description = "Id of an existing place to tag the post at", nullable = true)
                UUID placeId,
        @Schema(
                        description =
                                "Name of a place to tag the post at, created when new. Used when"
                                        + " placeId is absent.",
                        example = "Da Nang",
                        nullable = true)
                @Size(max = 100)
                String placeName,
        @Schema(description = "People tagged on the media items", nullable = true)
                @Size(max = 200)
                @Valid
                List<PostTagInput> tags,
        @Schema(
                        description =
                                "Accounts invited to co-author the post, at most three. They are"
                                        + " told when the post is published and appear only after"
                                        + " accepting.",
                        nullable = true)
                @Size(max = 3)
                List<UUID> collaboratorIds) {}
