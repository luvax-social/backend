package com.app.modules.notification.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Marks read every notification at or below {@code upTo}: the newest row the client rendered, so a
 * row that arrived after the client looked stays unread.
 */
@Schema(description = "Bound for mark-all-read: the newest row the client rendered")
public record ReadAllRequest(@NotNull @Valid NotificationPositionRequest upTo) {}
