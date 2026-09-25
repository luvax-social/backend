package com.app.modules.recommendation.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.app.modules.recommendation.enums.UserEventType;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * The single writer to {@code user_events}.
 *
 * <p>Three event types are recorded, chosen for investigative value per unit of write volume: a
 * session start on successful login, a search, and a view of somebody else's profile. Every other
 * value of the {@code event_type} enum exists in the schema and is deliberately not produced;
 * {@code post_view} in particular is an order of magnitude higher in volume than all three combined
 * and belongs to a later cycle. A fourth type is added by calling {@link #record} with it, not by
 * adding a fourth insert.
 *
 * <p>Three properties this component guarantees, in the order they matter.
 *
 * <p>It never fails the caller's request. Analytics that can 500 a login are strictly worse than no
 * analytics at all, so every failure path here ends in a warn log and a dropped row. There is no
 * retry, no outbox and no dead letter: {@code OutboxService} exists for events that must reach
 * RabbitMQ, and these are not those.
 *
 * <p>It never extends the caller's transaction. The insert runs on a virtual thread of its own, so
 * it takes its own connection and commits on its own; a caller inside a transaction that later
 * rolls back still leaves the event recorded, which is correct, because the thing being recorded is
 * that a request happened and not that it succeeded.
 *
 * <p>It never becomes the reason the database runs out of connections. Submission is bounded by a
 * permit count well under the Hikari pool size, and a submission with no permit free is dropped
 * immediately rather than queued or blocked. Backpressure onto a request thread would defeat the
 * first property.
 */
@Slf4j
@Component
public class UserEventRecorder {

    private static final String INSERT_SQL =
            """
			INSERT INTO user_events (user_id, event_type, entity_type, entity_id, metadata)
			VALUES (?, CAST(? AS event_type), ?, ?, CAST(? AS jsonb))
			""";

    /**
     * Maximum event writes in flight at once.
     *
     * <p>Sized well under the default Hikari pool of 20 so a burst of analytics writes cannot
     * starve request-serving work of connections. Exceeding it drops rows, which is the intended
     * failure mode.
     */
    private static final int MAX_IN_FLIGHT = 8;

    /** How long shutdown waits for writes already accepted to land before closing the executor. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SimpleAsyncTaskExecutor executor;
    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);

    public UserEventRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.executor = new SimpleAsyncTaskExecutor("user-event-");
        // Virtual threads are enabled globally, so no pool is needed: the task blocks on a database
        // round trip and nothing else, which is exactly what a virtual thread is for.
        this.executor.setVirtualThreads(true);
        // Carries the caller's observation into the insert so the row write stays in the request's
        // trace; it still commits on its own.
        this.executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
    }

    /** Records that an account started a session, called once a login has issued one. */
    public void recordSessionStart(UUID userId) {
        record(userId, UserEventType.SESSION_START, null, null, null);
    }

    /**
     * Records a search, carrying the term and the surface it was run against.
     *
     * @param userId the account that searched
     * @param scope which search surface produced it, {@code users} or {@code posts}
     * @param query the term as submitted; retained because a search log without the term answers no
     *     investigative question
     */
    public void recordSearch(UUID userId, String scope, String query) {
        record(userId, UserEventType.SEARCH, null, null, Map.of("scope", scope, "query", query));
    }

    /** Records that one account viewed another account's profile. */
    public void recordProfileView(UUID viewerId, UUID targetUserId) {
        record(viewerId, UserEventType.PROFILE_VIEW, "user", targetUserId, null);
    }

    /**
     * Queues one event for insertion on a thread of its own.
     *
     * @param userId the account the event is attributed to
     * @param eventType which event occurred
     * @param entityType the kind of thing acted on, null when the event names no target
     * @param entityId the thing acted on, null when the event names no target
     * @param metadata free-form detail stored as JSONB, null when there is none
     */
    public void record(
            UUID userId,
            UserEventType eventType,
            String entityType,
            UUID entityId,
            Map<String, Object> metadata) {
        if (userId == null || eventType == null) {
            return;
        }
        String metadataJson = serialize(metadata);
        if (!permits.tryAcquire()) {
            log.warn(
                    "Dropped {} event for user {}: {} event writes already in flight",
                    eventType,
                    userId,
                    MAX_IN_FLIGHT);
            return;
        }
        try {
            executor.execute(
                    () -> {
                        try {
                            jdbcTemplate.update(
                                    INSERT_SQL,
                                    userId,
                                    eventType.toJson(),
                                    entityType,
                                    entityId,
                                    metadataJson);
                        } catch (RuntimeException e) {
                            log.warn("Dropped {} event for user {}", eventType, userId, e);
                        } finally {
                            permits.release();
                        }
                    });
        } catch (RuntimeException e) {
            permits.release();
            log.warn("Could not queue {} event for user {}", eventType, userId, e);
        }
    }

    private String serialize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (RuntimeException e) {
            log.warn("Could not serialize event metadata; recording the event without it", e);
            return null;
        }
    }

    /**
     * Blocks until no event write is in flight, or the timeout elapses.
     *
     * <p>Acquiring every permit is the same thing as observing that nothing holds one, so this
     * settles without polling and without a sleep. Used on shutdown so writes already accepted are
     * not silently discarded, and by tests that need a deterministic point at which the
     * asynchronous writes are known to have landed.
     *
     * @param timeout how long to wait
     * @return true when every write finished, false when the timeout elapsed first
     */
    public boolean awaitQuiescence(Duration timeout) {
        try {
            if (!permits.tryAcquire(MAX_IN_FLIGHT, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return false;
            }
            permits.release(MAX_IN_FLIGHT);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        awaitQuiescence(SHUTDOWN_GRACE);
        executor.close();
    }
}
