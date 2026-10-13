package com.app.modules.social.dto.request;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Payload for turning on a temporary interaction limit.
 *
 * <p>The duration is a number of days rather than an end time, because the service computes the end
 * itself; a client clock never decides when a limit stops protecting the account.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Who to limit and for how many days")
public record UpdateInteractionLimitRequest(
        @Schema(
                        description = "Limit people who do not follow the caller",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Boolean limitNonFollowers,
        @Schema(
                        description =
                                "Limit people who started following the caller in the last 7 days",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Boolean limitRecentFollowers,
        @Schema(
                        description = "How long the limit lasts, in days",
                        allowableValues = {"1", "3", "7", "14", "28"},
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                Integer durationDays) {}
