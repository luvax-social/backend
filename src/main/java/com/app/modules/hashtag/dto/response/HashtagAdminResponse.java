package com.app.modules.hashtag.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.hashtag.enums.HashtagStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** A hashtag with its lifecycle state, for the administrative registry surface. */
@Schema(description = "Hashtag with its lifecycle state and the decision that set it")
public record HashtagAdminResponse(
        @Schema(
                        description = "Unique hashtag identifier",
                        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                UUID id,
        @Schema(
                        description = "Hashtag name without the # prefix, always lowercase",
                        example = "spring")
                String name,
        @Schema(
                        description =
                                "Number of published posts currently tagged with this hashtag;"
                                        + " trigger-maintained and unaffected by the status",
                        example = "142")
                int postCount,
        @Schema(description = "Lifecycle state") HashtagStatus status,
        @Schema(
                        description = "Administrator's justification for the current status",
                        example = "Coordinated harassment campaign",
                        nullable = true)
                String statusNote,
        @Schema(description = "When the current status was set", nullable = true)
                OffsetDateTime statusAt,
        @Schema(
                        description =
                                "Administrator that set the current status; null once that account"
                                        + " is deleted",
                        nullable = true)
                UUID statusBy,
        @Schema(description = "UTC timestamp when the hashtag was first created")
                OffsetDateTime createdAt,
        @Schema(description = "When an administrator pinned this hashtag; null when not pinned")
                OffsetDateTime pinnedAt,
        @Schema(description = "Administrator who pinned the hashtag; null when not pinned")
                UUID pinnedBy) {}
