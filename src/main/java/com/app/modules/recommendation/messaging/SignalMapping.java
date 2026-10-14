package com.app.modules.recommendation.messaging;

import java.util.UUID;
import java.util.function.Function;

import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.recommendation.enums.UserEventType;

/**
 * How one domain event type becomes a {@code user_events} row.
 *
 * <p>The account is always the envelope's actor and the time is always its {@code occurredAt}, so
 * neither is configurable here. The row id is the envelope's event id, which the consumer supplies
 * and which makes a redelivery write an identical row.
 *
 * @param eventType the stored type
 * @param entityType kind of thing the row is about
 * @param entityId reads the thing from the envelope
 * @param metadata builds the row's JSON text from fields the event carries
 */
public record SignalMapping(
        UserEventType eventType,
        String entityType,
        Function<DomainEventEnvelope, UUID> entityId,
        Function<DomainEventEnvelope, String> metadata) {

    /**
     * Reads a delivered event into the row it stands for.
     *
     * @param event the delivered envelope
     * @return the row to write
     * @throws PermanentMessageException when the envelope lacks an actor or a time, or the payload
     *     lacks a field the mapping needs, because redelivering it cannot make it valid
     */
    public ResolvedSignal resolve(DomainEventEnvelope event) {
        if (event.actorId() == null) {
            throw new PermanentMessageException("Signal " + event.eventType() + " has no actor");
        }
        if (event.occurredAt() == null) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has no occurredAt");
        }
        return new ResolvedSignal(
                event.actorId(),
                eventType,
                entityType,
                entityId.apply(event),
                metadata.apply(event),
                event.occurredAt());
    }
}
