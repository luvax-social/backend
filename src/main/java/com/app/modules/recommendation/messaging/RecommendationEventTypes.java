package com.app.modules.recommendation.messaging;

/** Versioned recommendation domain event types published through the transactional outbox. */
public final class RecommendationEventTypes {

    /**
     * Carries one behavioural event that must be stored in ClickHouse and never sent to Gorse.
     *
     * <p>It is the durable path for a server-side event that is not an engagement the recommender
     * should learn from: the development seed is its first user, and a later importer of historical
     * behaviour would be another. Every field of the row travels in the payload, so a redelivery
     * writes an identical row that the engine folds away.
     */
    public static final String USER_EVENT_IMPORTED_V1 = "recommendation.user-event.imported.v1";

    private RecommendationEventTypes() {}
}
