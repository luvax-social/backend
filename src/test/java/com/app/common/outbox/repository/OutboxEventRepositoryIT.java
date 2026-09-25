package com.app.common.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.enums.OutboxEventStatus;
import com.app.common.outbox.model.DomainEventEnvelope;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class OutboxEventRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private EntityManager entityManager;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void insertPending_usesDatabaseGeneratedPrimaryKeyAndPersistsJsonPayload() {
        OutboxEvent event = newEvent(OffsetDateTime.now(ZoneOffset.UTC), 0);

        OutboxEvent saved = outboxEventRepository.insertPending(event);
        entityManager.clear();
        OutboxEvent persisted = outboxEventRepository.findById(saved.getId()).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getPayload().eventId()).isEqualTo(event.getEventId());
        assertThat(persisted.getPayload().data())
                .containsEntry("userId", event.getAggregateId().toString());
    }

    @Test
    void claimPublishableBatch_returnsOnlyDueEventsAndMarksProcessing() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime claimUntil = now.plusMinutes(2);
        OutboxEvent due = outboxEventRepository.insertPending(newEvent(now.minusSeconds(1), 0));
        outboxEventRepository.insertPending(newEvent(now.plusMinutes(5), 0));

        List<OutboxEvent> events =
                outboxEventRepository.claimPublishableBatch(now, now, claimUntil, 100);

        assertThat(events).extracting(OutboxEvent::getId).containsExactly(due.getId());
        assertThat(events.getFirst().getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(events.getFirst().getClaimId()).isNotNull();
        assertThat(events.getFirst().getClaimedAt()).isNotNull();
        assertThat(events.getFirst().getClaimedUntil()).isNotNull();
        assertThat(events.getFirst().getNextRetryAt())
                .isAfter(claimUntil.minusSeconds(1))
                .isBefore(claimUntil.plusSeconds(1));
    }

    @Test
    void claimPublishableBatch_reclaimsExpiredProcessingEvents() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime firstClaimAt = now.minusSeconds(30);
        OffsetDateTime firstClaimUntil = now.minusSeconds(1);
        OffsetDateTime secondClaimUntil = now.plusMinutes(2);
        outboxEventRepository.insertPending(newEvent(now.minusMinutes(1), 0));
        OutboxEvent firstClaim =
                outboxEventRepository
                        .claimPublishableBatch(firstClaimAt, firstClaimAt, firstClaimUntil, 100)
                        .getFirst();

        List<OutboxEvent> reclaimed =
                outboxEventRepository.claimPublishableBatch(now, now, secondClaimUntil, 100);

        assertThat(reclaimed).extracting(OutboxEvent::getId).containsExactly(firstClaim.getId());
        assertThat(reclaimed.getFirst().getClaimId()).isNotEqualTo(firstClaim.getClaimId());
    }

    @Test
    void markPublished_setsPublishedStatusAndTimestamp() {
        OutboxEvent event = claimedEvent(0);
        OffsetDateTime publishedAt = OffsetDateTime.now(ZoneOffset.UTC);

        boolean marked =
                outboxEventRepository.markPublished(
                        event.getId(), event.getEventId(), event.getClaimId(), publishedAt);
        entityManager.clear();
        OutboxEvent persisted = outboxEventRepository.findById(event.getId()).orElseThrow();

        assertThat(marked).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(persisted.getPublishedAt()).isNotNull();
        assertThat(persisted.getLastError()).isNull();
    }

    @Test
    void markFailed_schedulesRetryAndStoresLastError() {
        OutboxEvent event = claimedEvent(0);
        OffsetDateTime nextRetryAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(10);

        boolean marked =
                outboxEventRepository.markFailed(
                        event.getId(),
                        event.getEventId(),
                        event.getClaimId(),
                        1,
                        nextRetryAt,
                        "broker unavailable");
        entityManager.clear();
        OutboxEvent persisted = outboxEventRepository.findById(event.getId()).orElseThrow();

        assertThat(marked).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
        assertThat(persisted.getNextRetryAt())
                .isAfter(nextRetryAt.minusSeconds(1))
                .isBefore(nextRetryAt.plusSeconds(1));
        assertThat(persisted.getClaimId()).isNull();
        assertThat(persisted.getClaimedAt()).isNull();
        assertThat(persisted.getClaimedUntil()).isNull();
        assertThat(persisted.getLastError()).isEqualTo("broker unavailable");
        assertThat(persisted.getPublishedAt()).isNull();
    }

    @Test
    void markDead_setsDeadStatusAndStoresLastError() {
        OutboxEvent event = claimedEvent(2);
        OffsetDateTime deadAt = OffsetDateTime.now(ZoneOffset.UTC);

        boolean marked =
                outboxEventRepository.markDead(
                        event.getId(), event.getEventId(), event.getClaimId(), 3, deadAt, "nack");
        entityManager.clear();
        OutboxEvent persisted = outboxEventRepository.findById(event.getId()).orElseThrow();

        assertThat(marked).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(persisted.getAttemptCount()).isEqualTo(3);
        assertThat(persisted.getNextRetryAt())
                .isAfter(deadAt.minusSeconds(1))
                .isBefore(deadAt.plusSeconds(1));
        assertThat(persisted.getLastError()).isEqualTo("nack");
        assertThat(persisted.getPublishedAt()).isNull();
    }

    @Test
    void insertPending_withTraceContext_roundTripsThroughInsertAndClaim() {
        String traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OutboxEvent event =
                newEvent(now.minusSeconds(1), 0).toBuilder()
                        .traceParent(traceParent)
                        .traceState("congo=t61")
                        .build();

        OutboxEvent saved = outboxEventRepository.insertPending(event);
        entityManager.clear();
        OutboxEvent persisted = outboxEventRepository.findById(saved.getId()).orElseThrow();
        List<OutboxEvent> claimed =
                outboxEventRepository.claimPublishableBatch(now, now, now.plusMinutes(2), 100);

        assertThat(saved.getTraceParent()).isEqualTo(traceParent);
        assertThat(saved.getTraceState()).isEqualTo("congo=t61");
        assertThat(persisted.getTraceParent()).isEqualTo(traceParent);
        assertThat(persisted.getTraceState()).isEqualTo("congo=t61");
        assertThat(claimed).extracting(OutboxEvent::getTraceParent).containsExactly(traceParent);
    }

    @Test
    void insertPending_withoutTraceContext_storesNullTraceColumns() {
        OutboxEvent saved =
                outboxEventRepository.insertPending(
                        newEvent(OffsetDateTime.now(ZoneOffset.UTC), 0));

        assertThat(saved.getTraceParent()).isNull();
        assertThat(saved.getTraceState()).isNull();
    }

    @Test
    void insertPending_malformedTraceparent_rejectedByCheckConstraint() {
        OutboxEvent event =
                newEvent(OffsetDateTime.now(ZoneOffset.UTC), 0).toBuilder()
                        .traceParent("not-a-w3c-traceparent")
                        .build();

        assertThatThrownBy(() -> outboxEventRepository.insertPending(event))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_events_trace_parent_format");
    }

    @Test
    void markPublished_returnsFalseWhenClaimIsNoLongerActive() {
        OutboxEvent event = claimedEvent(0);

        boolean marked =
                outboxEventRepository.markPublished(
                        event.getId(),
                        event.getEventId(),
                        UUID.randomUUID(),
                        OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(marked).isFalse();
    }

    private OutboxEvent claimedEvent(int attemptCount) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        outboxEventRepository.insertPending(newEvent(now.minusSeconds(1), attemptCount));
        return outboxEventRepository
                .claimPublishableBatch(now, now, now.plusMinutes(2), 100)
                .getFirst();
    }

    private OutboxEvent newEvent(OffsetDateTime nextRetryAt, int attemptCount) {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        DomainEventEnvelope payload =
                new DomainEventEnvelope(
                        eventId,
                        "user.registered.v1",
                        occurredAt,
                        aggregateId,
                        "user",
                        aggregateId,
                        Map.of("userId", aggregateId.toString()));
        return OutboxEvent.builder()
                .eventId(eventId)
                .aggregateType("user")
                .aggregateId(aggregateId)
                .eventType("user.registered.v1")
                .routingKey("user.registered.v1")
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(attemptCount)
                .nextRetryAt(nextRetryAt)
                .build();
    }
}
