package com.app.modules.post.place.service;

import java.util.UUID;

import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.enums.PlaceStatus;

/**
 * The outcome of a place status change, carrying what the administrative layer needs for its audit
 * row.
 *
 * @param placeId the place that changed
 * @param name the place name, for the audit metadata
 * @param previousStatus the status the place left
 * @param status the status it now holds
 * @param affectedPosts published posts that carry the place, which the change hides it from
 * @param response the place as the registry shows it afterwards
 */
public record PlaceLifecycleResult(
        UUID placeId,
        String name,
        PlaceStatus previousStatus,
        PlaceStatus status,
        int affectedPosts,
        PlaceAdminResponse response) {}
