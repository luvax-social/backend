package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** The outcome of a bounded mark-all-read. */
@Schema(description = "Outcome of mark-all-read")
public record ReadAllResponse(
        @Schema(
                        description = "Notifications that were unread and are now read",
                        requiredMode = REQUIRED)
                int updated) {}
