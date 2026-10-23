package com.app.modules.post.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for replacing the accounts invited to co-author a post")
public record ReplaceCollaboratorsRequest(
        @Schema(
                        description =
                                "The complete set of accounts that should hold a live invitation or"
                                        + " co-authorship, at most three. An account left out is"
                                        + " withdrawn or removed.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @Size(max = 3)
                List<UUID> userIds) {}
