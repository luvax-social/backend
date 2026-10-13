package com.app.modules.social.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Payload for adding a word or phrase to the caller's personal hidden words list.
 *
 * <p>The phrase is deliberately not bean-validated here: every rejection, a missing value included,
 * answers {@code HIDDEN_WORD_INVALID} so a client handles one error code.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Word or phrase to hide comments and message requests for")
public record CreateHiddenWordRequest(
        @Schema(description = "1 to 64 characters, normalising to 1 to 6 words", example = "đồ ngu")
                String phrase) {}
