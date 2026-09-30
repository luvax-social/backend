package com.app.common.seed.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import com.app.common.seed.outbox.SeedOutboxBatchWriter;
import com.app.common.seed.time.SeedTimeline;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;
import com.app.modules.recommendation.enums.UserEventType;

class AnalyticsSeedWriterTest {

    private static final Instant REFERENCE_NOW = Instant.parse("2026-08-25T10:17:00Z");
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    private JdbcTemplate jdbc;
    private SeedOutboxBatchWriter batchWriter;
    private AnalyticsSeedWriter writer;
    private List<UUID> userIds;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        batchWriter = mock(SeedOutboxBatchWriter.class);
        writer = new AnalyticsSeedWriter(jdbc, batchWriter);
        userIds = ids(40);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(50L);
        when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Map<String, Long>>>any()))
                .thenReturn(Map.of("active", 30L, "suspended", 5L));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(userIds);
        when(batchWriter.emitUserEventImportBatch(any()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    }

    @Test
    void write_enqueuesNinetyDaysOfHalfHourBucketsEachEndingBeforeReferenceNow() {
        List<PlatformStatsCollectedEvent.Bucket> buckets = new ArrayList<>();
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            buckets.addAll(invocation.getArgument(0));
                            return null;
                        })
                .when(batchWriter)
                .emitPlatformStatsBatch(any());

        AnalyticsSeedWriter.AnalyticsCounts counts =
                writer.write(new SeedTimeline(20260825L, REFERENCE_NOW));

        assertThat(counts.statsBuckets()).isEqualTo(90 * 48).isEqualTo(buckets.size());
        assertThat(buckets)
                .allSatisfy(
                        bucket ->
                                assertThat(bucket.bucketStart().plus(HALF_HOUR))
                                        .isBeforeOrEqualTo(REFERENCE_NOW));
        assertThat(buckets.stream().map(PlatformStatsCollectedEvent.Bucket::bucketStart))
                .doesNotHaveDuplicates()
                .isSorted();
        assertThat(buckets.get(buckets.size() - 1).bucketStart())
                .isEqualTo(Instant.parse("2026-08-25T09:30:00Z"));
    }

    @Test
    void generateUserEvents_coversEveryTypeButPostViewAtLeastFiveTimes() {
        List<SeedOutboxBatchWriter.UserEventImport> events = generate();

        Map<UserEventType, Integer> counts = new EnumMap<>(UserEventType.class);
        events.forEach(event -> counts.merge(event.eventType(), 1, Integer::sum));
        for (UserEventType type : UserEventType.values()) {
            if (type == UserEventType.POST_VIEW) {
                assertThat(counts).doesNotContainKey(type);
            } else {
                assertThat(counts.getOrDefault(type, 0))
                        .as("events of type %s", type.toJson())
                        .isGreaterThanOrEqualTo(AnalyticsSeedWriter.MIN_EVENTS_PER_TYPE);
            }
        }
        AnalyticsSeedWriter.assertEveryProducedTypeCovered(events);
    }

    @Test
    void generateUserEvents_keepsEveryEventInsideTheLookbackWithADistinctStableId() {
        List<SeedOutboxBatchWriter.UserEventImport> first = generate();
        List<SeedOutboxBatchWriter.UserEventImport> second = generate();

        assertThat(first).hasSize(10_000);
        Set<UUID> eventIds = new HashSet<>();
        first.forEach(event -> eventIds.add(event.eventId()));
        assertThat(eventIds).hasSize(first.size());
        assertThat(first)
                .allSatisfy(
                        event ->
                                assertThat(event.createdAt())
                                        .isAfterOrEqualTo(REFERENCE_NOW.minus(Duration.ofDays(90)))
                                        .isBeforeOrEqualTo(REFERENCE_NOW));
        assertThat(second.stream().map(SeedOutboxBatchWriter.UserEventImport::eventId).toList())
                .isEqualTo(
                        first.stream()
                                .map(SeedOutboxBatchWriter.UserEventImport::eventId)
                                .toList());
    }

    @Test
    void assertEveryProducedTypeCovered_withATypeMissing_namesIt() {
        List<SeedOutboxBatchWriter.UserEventImport> events =
                new ArrayList<>(
                        generate().stream()
                                .filter(e -> e.eventType() != UserEventType.SEARCH)
                                .toList());

        assertThatThrownBy(() -> AnalyticsSeedWriter.assertEveryProducedTypeCovered(events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search=0");
    }

    private List<SeedOutboxBatchWriter.UserEventImport> generate() {
        return writer.generateUserEvents(
                new SeedTimeline(20260825L, REFERENCE_NOW),
                new Random(8_836_215L),
                userIds,
                ids(60),
                ids(20),
                ids(10));
    }

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.nameUUIDFromBytes(("id-" + i).getBytes()));
        }
        return ids;
    }
}
