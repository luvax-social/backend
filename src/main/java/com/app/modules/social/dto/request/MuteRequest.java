package com.app.modules.social.dto.request;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Payload for setting what a mute hides.
 *
 * <p>Both scopes are required so a client states the whole choice every time. Turning both off is
 * an unmute, which removes the mute instead of storing a row that hides nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Which of the account's content to mute")
public record MuteRequest(
        @Schema(
                        description = "Hide the account's posts from the caller's surfaces",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Boolean mutePosts,
        @Schema(
                        description = "Hide the account's stories from the caller's surfaces",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Boolean muteStories) {}
