package com.app.modules.post.place.event;

import java.util.UUID;

/** Payload of {@code place.index.delete.v1}: the place whose index document is removed. */
public record PlaceIndexDeleteEvent(UUID placeId) {}
