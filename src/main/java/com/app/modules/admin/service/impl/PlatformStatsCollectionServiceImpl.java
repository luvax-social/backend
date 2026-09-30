package com.app.modules.admin.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.admin.config.StatsProperties;
import com.app.modules.admin.enums.PlatformMetric;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;
import com.app.modules.admin.repository.PlatformStatsRepository;
import com.app.modules.admin.service.PlatformStatsCollectionService;

@Service
public class PlatformStatsCollectionServiceImpl implements PlatformStatsCollectionService {

    private final PlatformStatsRepository platformStatsRepository;
    private final OutboxService outboxService;
    private final StatsProperties properties;

    public PlatformStatsCollectionServiceImpl(
            PlatformStatsRepository platformStatsRepository,
            OutboxService outboxService,
            StatsProperties properties) {
        this.platformStatsRepository = platformStatsRepository;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    // The metric statements and the event are in one transaction, so the bucket the event carries
    // was read from one consistent snapshot and the event exists only if that read completed.
    @Override
    @Transactional
    public int collectBucket(OffsetDateTime bucketStart) {
        OffsetDateTime bucketEnd = bucketStart.plus(properties.interval());
        List<PlatformStatsCollectedEvent.Row> rows = new ArrayList<>();
        for (PlatformMetric metric : PlatformMetric.values()) {
            platformStatsRepository
                    .compute(metric, bucketStart, bucketEnd)
                    .forEach(
                            value ->
                                    rows.add(
                                            new PlatformStatsCollectedEvent.Row(
                                                    metric.key(),
                                                    value.dimension(),
                                                    value.value())));
        }
        Instant start = bucketStart.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
        outboxService.enqueue(
                AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                PlatformStatsCollectedEvent.AGGREGATE_TYPE,
                PlatformStatsCollectedEvent.aggregateId(start),
                null,
                PlatformStatsCollectedEvent.payload(
                        start, properties.interval().toSeconds(), Instant.now(), rows));
        return rows.size();
    }
}
