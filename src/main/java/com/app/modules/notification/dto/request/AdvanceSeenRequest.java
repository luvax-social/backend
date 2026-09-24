package com.app.modules.notification.dto.request;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The newest notification the client rendered, from which the seen watermark advances.
 *
 * @param activityAt the rendered row's {@code activityAt}; clamped server-side to the row's current
 *     value and to the database clock
 * @param id the rendered row's id; must belong to the caller
 */
@Schema(description = "The newest notification the client rendered")
public record AdvanceSeenRequest(@NotNull OffsetDateTime activityAt, @NotNull UUID id) {}
