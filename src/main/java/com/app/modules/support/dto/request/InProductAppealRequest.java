package com.app.modules.support.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An appeal opened from a session, against an audit row the caller owns.
 *
 * <p>The counterpart to {@code SignedAppealRequest}, for the appellant who still holds a session
 * and therefore never needed a signed link. It carries an audit row identifier where that record
 * carries a token, and the two are not interchangeable: a token is a bearer credential that proves
 * what it authorises, while this identifier proves nothing at all. Ownership is established
 * server-side by reading {@code admin_actions.target_user_id}, never from this request.
 *
 * <p>Carries no category, for the same reason the signed record does not: the category follows from
 * the action the audit row records.
 *
 * @param adminActionId the audit row being appealed
 * @param subject one-line summary
 * @param body the appeal itself
 */
public record InProductAppealRequest(
        @Schema(
                        description = "The moderation decision being appealed",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                UUID adminActionId,
        @Schema(description = "One-line summary", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 200)
                String subject,
        @Schema(description = "The appeal itself", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 5000)
                String body) {}
