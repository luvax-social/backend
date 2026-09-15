package com.app.modules.report.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.app.modules.report.enums.ReportReason;
import com.app.modules.report.enums.ReportType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload for reporting a post, comment, user, story, or message")
public record CreateReportRequest(
        @Schema(
                        description = "Type of entity being reported",
                        example = "post",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                ReportType reportType,
        @Schema(
                        description = "Reason for the report",
                        example = "spam",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                ReportReason reportReason,
        @Schema(
                        description = "Identifier of the entity being reported",
                        example = "550e8400-e29b-41d4-a716-446655440000",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                UUID entityId,
        @Schema(
                        description = "Optional context supplied by the reporter",
                        example = "Repeated unsolicited promotional content")
                @Size(max = 2000)
                String description,
        @Schema(
                        description =
                                "Cloudflare Turnstile token from the widget in the report dialog."
                                        + " Carries no @NotBlank on purpose: a blank token is"
                                        + " refused by the verifier, which is what lets"
                                        + " app.turnstile.auth.enabled switch the check off.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @Size(max = 2048)
                String turnstileToken) {}
