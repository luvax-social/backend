package com.app.common.retention.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.app.common.inbox.repository.ProcessedMessageRepository;
import com.app.common.outbox.repository.OutboxEventRepository;
import com.app.common.retention.RetentionProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeServiceImplTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ProcessedMessageRepository processedMessageRepository;
    @Mock private ObjectProvider<RetentionPurgeServiceImpl> selfProvider;

    private RetentionProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private RetentionPurgeServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new RetentionProperties();
        properties.setBatchSize(10);
        properties.setMaxBatchesPerRun(3);
        meterRegistry = new SimpleMeterRegistry();
        service =
                new RetentionPurgeServiceImpl(
                        outboxEventRepository,
                        processedMessageRepository,
                        properties,
                        meterRegistry,
                        selfProvider);
        when(selfProvider.getObject()).thenReturn(service);
    }

    @Test
    void purge_shortBatch_stopsLooping() {
        when(outboxEventRepository.deletePublishedBefore(any(), eq(10))).thenReturn(4);
        when(processedMessageRepository.deleteProcessedBefore(any(), eq(10))).thenReturn(0);

        service.purge();

        verify(outboxEventRepository, times(1)).deletePublishedBefore(any(), anyInt());
        assertThat(
                        meterRegistry
                                .get("luvax.retention.deleted")
                                .tag("table", "outbox_events")
                                .counter()
                                .count())
                .isEqualTo(4.0);
    }

    @Test
    void purge_fullBatchesEveryTime_stopsAtMaxBatchesPerRun() {
        when(outboxEventRepository.deletePublishedBefore(any(), eq(10))).thenReturn(10);
        when(processedMessageRepository.deleteProcessedBefore(any(), eq(10))).thenReturn(10);

        service.purge();

        verify(outboxEventRepository, times(3)).deletePublishedBefore(any(), anyInt());
        assertThat(
                        meterRegistry
                                .get("luvax.retention.deleted")
                                .tag("table", "outbox_events")
                                .counter()
                                .count())
                .isEqualTo(30.0);
    }

    @Test
    void purge_bothTables_incrementDeletedCounterPerTable() {
        when(outboxEventRepository.deletePublishedBefore(any(), eq(10))).thenReturn(2);
        when(processedMessageRepository.deleteProcessedBefore(any(), eq(10))).thenReturn(5);

        service.purge();

        assertThat(
                        meterRegistry
                                .get("luvax.retention.deleted")
                                .tag("table", "outbox_events")
                                .counter()
                                .count())
                .isEqualTo(2.0);
        assertThat(
                        meterRegistry
                                .get("luvax.retention.deleted")
                                .tag("table", "processed_messages")
                                .counter()
                                .count())
                .isEqualTo(5.0);
    }
}
