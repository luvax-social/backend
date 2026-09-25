package com.app.common.outbox.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.app.common.observability.TraceContextCapture;
import com.app.common.observability.W3cTraceContext;
import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.enums.OutboxEventStatus;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.repository.OutboxEventRepository;
import com.app.common.outbox.service.OutboxService;

@Service
public class OutboxServiceImpl implements OutboxService {

    private static final String SENSITIVE_DATA_KEY_MESSAGE =
            "Outbox event data must not contain token, password, secret, or credential fields";
    private static final String SENSITIVE_DATA_VALUE_MESSAGE =
            "Outbox event data must not contain raw tokens, passwords, secrets, credentials, or URLs carrying them";

    private final OutboxEventRepository outboxEventRepository;
    private final TraceContextCapture traceContextCapture;

    public OutboxServiceImpl(
            OutboxEventRepository outboxEventRepository, TraceContextCapture traceContextCapture) {
        this.outboxEventRepository = outboxEventRepository;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    // Enqueue must join the business transaction so DB writes and event intent commit together.
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(
            String eventType,
            String routingKey,
            String aggregateType,
            UUID aggregateId,
            UUID actorId,
            Map<String, Object> data) {
        Assert.hasText(eventType, "eventType must not be blank");
        Assert.hasText(routingKey, "routingKey must not be blank");
        Assert.hasText(aggregateType, "aggregateType must not be blank");
        Assert.notNull(aggregateId, "aggregateId must not be null");

        UUID eventId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> eventData = data == null ? Map.of() : Map.copyOf(data);
        validateNoSensitiveDataKeys(eventData);
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        eventType,
                        occurredAt,
                        actorId,
                        aggregateType,
                        aggregateId,
                        eventData);

        Optional<W3cTraceContext> trace = traceContextCapture.captureCurrent();
        OutboxEvent event =
                OutboxEvent.builder()
                        .eventId(eventId)
                        .aggregateType(aggregateType)
                        .aggregateId(aggregateId)
                        .eventType(eventType)
                        .routingKey(routingKey)
                        .payload(envelope)
                        .status(OutboxEventStatus.PENDING)
                        .attemptCount(0)
                        .nextRetryAt(occurredAt)
                        .traceParent(trace.map(W3cTraceContext::traceParent).orElse(null))
                        .traceState(trace.map(W3cTraceContext::traceState).orElse(null))
                        .build();
        return outboxEventRepository.insertPending(event);
    }

    @Override
    // Enqueue must join the business transaction so DB writes and event intent commit together.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueueOnce(
            UUID eventId,
            String eventType,
            String routingKey,
            String aggregateType,
            UUID aggregateId,
            UUID actorId,
            Map<String, Object> data) {
        Assert.notNull(eventId, "eventId must not be null");
        Assert.hasText(eventType, "eventType must not be blank");
        Assert.hasText(routingKey, "routingKey must not be blank");
        Assert.hasText(aggregateType, "aggregateType must not be blank");
        Assert.notNull(aggregateId, "aggregateId must not be null");

        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> eventData = data == null ? Map.of() : Map.copyOf(data);
        validateNoSensitiveDataKeys(eventData);
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        eventType,
                        occurredAt,
                        actorId,
                        aggregateType,
                        aggregateId,
                        eventData);

        Optional<W3cTraceContext> trace = traceContextCapture.captureCurrent();
        OutboxEvent event =
                OutboxEvent.builder()
                        .eventId(eventId)
                        .aggregateType(aggregateType)
                        .aggregateId(aggregateId)
                        .eventType(eventType)
                        .routingKey(routingKey)
                        .payload(envelope)
                        .status(OutboxEventStatus.PENDING)
                        .attemptCount(0)
                        .nextRetryAt(occurredAt)
                        .traceParent(trace.map(W3cTraceContext::traceParent).orElse(null))
                        .traceState(trace.map(W3cTraceContext::traceState).orElse(null))
                        .build();
        return outboxEventRepository.insertPendingIgnoreDuplicate(event).isPresent();
    }

    private void validateNoSensitiveDataKeys(Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            Assert.isTrue(!isSensitiveKey(key), SENSITIVE_DATA_KEY_MESSAGE);
            validateNestedSensitiveDataKeys(entry.getValue());
        }
    }

    private void validateNestedSensitiveDataKeys(Object value) {
        if (value instanceof CharSequence text) {
            validateNoSensitiveStringValue(text.toString());
            return;
        }
        if (value instanceof Map<?, ?> nestedData) {
            validateNoSensitiveDataKeys(castNestedData(nestedData));
            return;
        }
        if (value instanceof Iterable<?> values) {
            values.forEach(this::validateNestedSensitiveDataKeys);
        }
    }

    private boolean isSensitiveKey(String key) {
        return key.contains("token")
                || key.contains("password")
                || key.contains("secret")
                || key.contains("credential");
    }

    private void validateNoSensitiveStringValue(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        Assert.isTrue(!containsSensitiveValue(normalized), SENSITIVE_DATA_VALUE_MESSAGE);
    }

    private boolean containsSensitiveValue(String value) {
        return value.contains("bearer ")
                || value.contains("token=")
                || value.contains("password=")
                || value.contains("secret=")
                || value.contains("credential=")
                || value.contains("access_token")
                || value.contains("refresh_token")
                || value.contains("reset_token")
                || value.contains("verification_token");
    }

    private Map<String, Object> castNestedData(Map<?, ?> data) {
        for (Object key : data.keySet()) {
            Assert.isInstanceOf(String.class, key, "Outbox event data keys must be strings");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typedData = (Map<String, Object>) data;
        return typedData;
    }
}
