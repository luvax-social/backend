package com.app.modules.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A phrase for the platform-wide hidden word list, with the justification the audit row keeps.
 *
 * <p>The phrase is deliberately not bean-validated: every rejection of it, a missing value
 * included, answers {@code HIDDEN_WORD_INVALID}, the one code the personal list uses as well.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "A phrase for the platform-wide hidden word list")
public record AdminHiddenWordRequest(
        @Schema(description = "1 to 64 characters, normalising to 1 to 6 words", example = "đồ ngu")
                String phrase,
        @Schema(
                        description = "Justification recorded on the audit row",
                        example = "Abuse reported repeatedly in comments",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 2000)
                String reason) {}
