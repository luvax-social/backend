package com.app.modules.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Justification for removing a phrase from the platform-wide hidden word list.
 *
 * <p>The removal is a hard delete of a configuration row, so the audit row's reason and metadata
 * are the only record that the phrase existed.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Justification for removing a hidden word")
public record AdminDeleteHiddenWordRequest(
        @Schema(
                        description = "Justification recorded on the audit row",
                        example = "Common word, hid too many ordinary comments",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 2000)
                String reason) {}
