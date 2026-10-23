package com.app.modules.admin.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.app.modules.post.place.enums.PlaceStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lifecycle transition for one existing place.
 *
 * <p>Carries no {@code name} field, which is how the name is made immutable: a place's name is its
 * identity, and renaming would silently relabel other people's posts. Request DTOs reject
 * unrecognised properties, so a body carrying {@code name} fails deserialization instead of being
 * discarded.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Lifecycle transition for one place")
public record AdminUpdatePlaceRequest(
        @Schema(
                        description =
                                "State to move the place to; must differ from its current one."
                                        + " Moving a banned or deleted place to active is the unban.",
                        example = "banned",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                PlaceStatus status,
        @Schema(
                        description =
                                "Justification recorded on the place row and the audit row;"
                                        + " required to ban or delete, optional to unban",
                        example = "Advertises a follower-selling service",
                        nullable = true)
                @Size(max = 2000)
                String note,
        @Schema(
                        description =
                                "A report about this place that the action resolves in the same"
                                        + " transaction when it bans or deletes the place",
                        nullable = true)
                UUID reportId) {}
