package com.app.modules.social.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The caller's personal hidden words list, newest entry first, with the size limit. */
@Schema(description = "The caller's personal hidden words list")
public record HiddenWordListResponse(
        @Schema(description = "Entries, newest first") List<HiddenWordEntryResponse> entries,
        @Schema(description = "Most entries one account may keep", example = "200") int limit) {}
