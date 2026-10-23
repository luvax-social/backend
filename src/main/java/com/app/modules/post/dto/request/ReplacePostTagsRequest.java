package com.app.modules.post.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for replacing the people tagged on a post")
public record ReplacePostTagsRequest(
        @Schema(
                        description = "The complete tag set; an empty list removes every tag",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @Size(max = 200)
                @Valid
                List<PostTagInput> tags) {}
