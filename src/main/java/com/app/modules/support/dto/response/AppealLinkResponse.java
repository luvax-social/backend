package com.app.modules.support.dto.response;

import com.app.modules.support.enums.SupportCategory;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an unredeemed appeal link authorises, without redeeming it.
 *
 * <p>Deliberately narrow. It names the appeal category so the landing screen can say which decision
 * is being appealed, and nothing else - no account id, no email, no audit row. A caller holding the
 * token learns only what the form was going to tell them anyway.
 *
 * @param category the appeal category the moderation action implies
 */
@Schema(description = "What a still-valid appeal link authorises")
public record AppealLinkResponse(
        @Schema(description = "The appeal category the link is for", example = "APPEAL_BAN")
                SupportCategory category) {}
