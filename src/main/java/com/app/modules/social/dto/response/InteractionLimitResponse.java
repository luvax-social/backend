package com.app.modules.social.dto.response;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/** The caller's temporary interaction limit, as stored and as it stands right now. */
@Schema(description = "The caller's temporary interaction limit")
public record InteractionLimitResponse(
        @Schema(description = "Limit people who do not follow the caller")
                boolean limitNonFollowers,
        @Schema(description = "Limit people who started following the caller in the last 7 days")
                boolean limitRecentFollowers,
        @Schema(
                        description = "When the limit ends, or null when none was ever set",
                        nullable = true)
                OffsetDateTime expiresAt,
        @Schema(
                        description =
                                "True while the limit is in force, that is while expiresAt is in"
                                        + " the future; an expired limit reads false without any job"
                                        + " having run")
                boolean active) {}
