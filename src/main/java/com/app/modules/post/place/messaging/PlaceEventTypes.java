package com.app.modules.post.place.messaging;

/**
 * Versioned place domain event types published through the transactional outbox.
 *
 * <p>The {@code place.index.*} namespace feeds the Elasticsearch sync queue, mirroring {@code
 * hashtag.index.*}. Every event carries the place id and nothing else: the consumer reads the name,
 * count and status from the database, so a status change reuses the upsert event.
 */
public final class PlaceEventTypes {

    public static final String PLACE_INDEX_UPSERT_V1 = "place.index.upsert.v1";
    public static final String PLACE_INDEX_DELETE_V1 = "place.index.delete.v1";

    private PlaceEventTypes() {}
}
