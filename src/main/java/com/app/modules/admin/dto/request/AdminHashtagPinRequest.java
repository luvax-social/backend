package com.app.modules.admin.dto.request;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optional note recorded on the audit row for a hashtag pin or unpin.
 *
 * <p>The note is optional where a ban's reason is mandatory. Pinning grants prominence rather than
 * taking a capability away, which is the line {@code moderation_action_configs.requires_reason}
 * draws, and both pin actions are registered with {@code requires_reason = FALSE}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Optional note for a hashtag pin or unpin")
public record AdminHashtagPinRequest(
        @Schema(
                        description = "Free-text note recorded on the audit row",
                        example = "Featured for the launch week")
                @Size(max = 2000)
                String note) {}
