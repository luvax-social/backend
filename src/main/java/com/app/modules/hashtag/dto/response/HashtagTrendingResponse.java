package com.app.modules.hashtag.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.hashtag.enums.TrendingSource;

import io.swagger.v3.oas.annotations.media.Schema;

/** A hashtag's ranking within a single trending snapshot window. */
@Schema(description = "A hashtag's rank and post count within a trending window")
public record HashtagTrendingResponse(
        @Schema(
                        description = "Unique hashtag identifier",
                        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                UUID hashtagId,
        @Schema(description = "Hashtag name without the # prefix", example = "spring") String name,
        @Schema(
                        description =
                                "Number of posts tagged during the trending window. Null when this"
                                        + " hashtag is not in the current snapshot and therefore"
                                        + " has no window count; render it as new rather than"
                                        + " substituting a lifetime total, which is a different"
                                        + " measurement and not comparable with the others in the"
                                        + " list.",
                        example = "87",
                        nullable = true)
                Integer postCount,
        @Schema(
                        description = "Rank within this trending snapshot; 1 is the most popular",
                        example = "1")
                int rank,
        @Schema(
                        description = "Start of the trending window (UTC)",
                        example = "2024-01-15T00:00:00Z")
                OffsetDateTime periodStart,
        @Schema(description = "End of the trending window (UTC)", example = "2024-01-16T00:00:00Z")
                OffsetDateTime periodEnd,
        @Schema(description = "Whether an administrator has pinned this hashtag platform-wide")
                boolean pinned,
        @Schema(
                        description =
                                "Why this hashtag is in the list: platform for the platform-wide"
                                        + " snapshot, affinity for the caller's own interests, novel"
                                        + " for a hashtag adjacent to them but not among them")
                TrendingSource source) {}
