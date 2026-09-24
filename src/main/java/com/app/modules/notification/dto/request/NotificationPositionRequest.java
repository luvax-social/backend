package com.app.modules.notification.dto.request;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** A position in the notification feed, the {@code (activityAt, id)} tuple that orders it. */
@Schema(description = "A position in the notification feed")
public record NotificationPositionRequest(@NotNull OffsetDateTime activityAt, @NotNull UUID id) {}
