package com.app.modules.post.place.service;

import static com.app.modules.post.place.messaging.PlaceEventTypes.PLACE_INDEX_DELETE_V1;
import static com.app.modules.post.place.messaging.PlaceEventTypes.PLACE_INDEX_UPSERT_V1;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.common.outbox.service.OutboxService;

/**
 * Single point that enqueues place search-index events.
 *
 * <p>Carries no transaction annotation so every call joins the caller's transaction, which is what
 * {@code OutboxService.enqueue} requires. Every event carries the place id and nothing else; the
 * consumer reads name, {@code post_count} and {@code status} from the database, so it decides
 * between indexing and dropping the document against current state, and a status change reuses the
 * upsert event.
 */
@Component
public class PlaceIndexEventPublisher {

    private static final String AGGREGATE_TYPE_PLACE = "place";

    private final OutboxService outboxService;

    public PlaceIndexEventPublisher(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * Enqueues a sync event that leaves the index decision to the consumer.
     *
     * @param placeId the place whose index document should be reconciled
     */
    public void enqueueSync(UUID placeId) {
        enqueue(PLACE_INDEX_UPSERT_V1, placeId);
    }

    /**
     * Enqueues an unconditional index delete.
     *
     * @param placeId the place whose index document should be removed
     */
    public void enqueueDelete(UUID placeId) {
        enqueue(PLACE_INDEX_DELETE_V1, placeId);
    }

    private void enqueue(String eventType, UUID placeId) {
        outboxService.enqueue(
                eventType,
                eventType,
                AGGREGATE_TYPE_PLACE,
                placeId,
                null,
                Map.of("placeId", placeId.toString()));
    }
}
