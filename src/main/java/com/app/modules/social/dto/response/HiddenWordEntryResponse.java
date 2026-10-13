package com.app.modules.social.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One entry of a personal hidden words list, with the phrase as the account typed it. */
@Schema(description = "One entry of the caller's personal hidden words list")
public record HiddenWordEntryResponse(
        @Schema(description = "Entry identifier") UUID id,
        @Schema(description = "The phrase as typed", example = "Đồ Ngu") String phrase,
        @Schema(description = "When the entry was added") OffsetDateTime createdAt) {}
