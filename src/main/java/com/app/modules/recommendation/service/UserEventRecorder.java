package com.app.modules.recommendation.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.stereotype.Component;

import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.observability.AnalyticsMetrics;
import com.app.common.analytics.observability.AnalyticsMetrics.DropReason;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * The single request-path writer to {@code user_events}, the behavioural event store in ClickHouse.
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
 * RabbitMQ, and these are not those. Each drop is counted by reason, and a row lost inside
 * ClickHouse's own flush window is counted by ClickHouse's failed-insert counter, which is what the
 * alert on silent losses reads.
 *
 * <p>It never extends the caller's transaction. The insert runs on a virtual thread of its own and
 * does not wait for ClickHouse to flush it; a caller inside a transaction that later rolls back
 * still leaves the event recorded, which is correct, because the thing being recorded is that a
 * request happened and not that it succeeded.
 *
 * <p>It never becomes the reason a request slows down or the analytics pool runs dry. Submission is
 * bounded by a permit count well under the writer pool size, and a submission with no permit free
 * is dropped immediately rather than queued or blocked. Backpressure onto a request thread would
 * defeat the first property.
 */
@Slf4j
@Component
public class UserEventRecorder {

    /**
     * Maximum event writes in flight at once.
     *
     * <p>Sized well under the analytics writer pool of 12, which the ingestion consumers share, so
     * a burst of recorded events cannot starve them of connections. Exceeding it drops rows, which
     * is the intended failure mode.
     */
    private static final int MAX_IN_FLIGHT = 8;

    /** How long shutdown waits for writes already accepted to land before closing the executor. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final UserEventAnalyticsRepository analyticsRepository;
    private final AnalyticsMetrics metrics;
    private final ObjectMapper objectMapper;
    private final SimpleAsyncTaskExecutor executor;
    private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);

    public UserEventRecorder(
            UserEventAnalyticsRepository analyticsRepository,
            AnalyticsMetrics metrics,
            ObjectMapper objectMapper) {
        this.analyticsRepository = analyticsRepository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.executor = new SimpleAsyncTaskExecutor("user-event-");
        // Virtual threads are enabled globally, so no pool is needed: the task blocks on one
        // ClickHouse round trip and nothing else, which is exactly what a virtual thread is for.
        this.executor.setVirtualThreads(true);
        // Carries the caller's observation into the insert so the row write stays in the request's
        // trace.
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
     * @param metadata free-form detail stored as JSON text, null when there is none
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
        // Taken here, on the request thread: the stored time is when the request happened, not
        // when the buffered insert is flushed.
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (!permits.tryAcquire()) {
            metrics.recordUserEventDropped(DropReason.PERMITS);
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
                            analyticsRepository.recordDetached(
                                    userId,
                                    eventType,
                                    entityType,
                                    entityId,
                                    metadataJson,
                                    createdAt);
                        } catch (ClickHouseUnavailableException e) {
                            metrics.recordUserEventDropped(dropReason(e));
                            log.warn(
                                    "Dropped {} event for user {}: {}",
                                    eventType,
                                    userId,
                                    e.getMessage());
                        } catch (RuntimeException e) {
                            metrics.recordUserEventDropped(DropReason.ERROR);
                            log.warn("Dropped {} event for user {}", eventType, userId, e);
                        } finally {
                            permits.release();
                        }
                    });
        } catch (RuntimeException e) {
            permits.release();
            metrics.recordUserEventDropped(DropReason.ERROR);
            log.warn("Could not queue {} event for user {}", eventType, userId, e);
        }
    }

    // A schema that is not ready and an open breaker each have their own reason, because neither
    // is a fault worth alerting on; a server failure is not distinguishable from any other error.
    private static DropReason dropReason(ClickHouseUnavailableException e) {
        return switch (e.reason()) {
            case NOT_READY -> DropReason.NOT_READY;
            case CIRCUIT_OPEN -> DropReason.CIRCUIT_OPEN;
            case SERVER -> DropReason.ERROR;
        };
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
