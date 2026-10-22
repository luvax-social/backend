package com.app.modules.post.place.event;

import java.util.UUID;

/** Payload of {@code place.index.upsert.v1}: the place to reconcile with the search index. */
public record PlaceIndexUpsertEvent(UUID placeId) {}
