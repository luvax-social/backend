package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.admin.config.StatsProperties;
import com.app.modules.admin.service.PlatformStatsCollectionService;

@ExtendWith(MockitoExtension.class)
class StatsCollectionJobTest {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Mock private PlatformStatsCollectionService collectionService;

    private StatsCollectionJob jobStartedAt(String startedAt) {
        StatsProperties properties = new StatsProperties(true, HALF_HOUR, Duration.ofDays(30));
        return new StatsCollectionJob(collectionService, properties, Instant.parse(startedAt));
    }

    @Test
    void collectDue_bucketTheProcessStartedInside_isNeverWritten() {
        // Started at 10:17, so the 10:00 bucket was only partly observed. At 10:59 the 10:00 bucket
        // is the only complete one, and it is precisely the one that must be skipped.
        StatsCollectionJob job = jobStartedAt("2026-08-19T10:17:00Z");

        int written = job.collectDue(Instant.parse("2026-08-19T10:59:00Z"));

        assertThat(written).isZero();
        verify(collectionService, never()).collectBucket(any());
    }

    @Test
    void collectDue_firstCompleteBucketAfterStart_isWritten() {
        StatsCollectionJob job = jobStartedAt("2026-08-19T10:17:00Z");

        int written = job.collectDue(Instant.parse("2026-08-19T11:05:00Z"));

        assertThat(written).isEqualTo(1);
        verify(collectionService).collectBucket(OffsetDateTime.parse("2026-08-19T10:30:00Z"));
    }

    @Test
    void collectDue_bucketInProgress_isNotWritten() {
        StatsCollectionJob job = jobStartedAt("2026-08-19T10:30:00Z");

        job.collectDue(Instant.parse("2026-08-19T11:29:59Z"));

        // 11:00 is still running at 11:29:59, so only 10:30 has ended.
        verify(collectionService).collectBucket(OffsetDateTime.parse("2026-08-19T10:30:00Z"));
        verifyNoMoreInteractions(collectionService);
    }

    @Test
    void collectDue_missedPasses_fillEveryBucketRatherThanOnlyTheNewest() {
        // A pass that wrote only the most recent complete bucket would leave a permanent hole for
        // every bucket the outage covered, and nothing backfills this table.
        StatsCollectionJob job = jobStartedAt("2026-08-19T10:30:00Z");

        int written = job.collectDue(Instant.parse("2026-08-19T13:05:00Z"));

        ArgumentCaptor<OffsetDateTime> buckets = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(collectionService, times(5)).collectBucket(buckets.capture());
        assertThat(written).isEqualTo(5);
        assertThat(buckets.getAllValues())
                .containsExactly(
                        OffsetDateTime.parse("2026-08-19T10:30:00Z"),
                        OffsetDateTime.parse("2026-08-19T11:00:00Z"),
                        OffsetDateTime.parse("2026-08-19T11:30:00Z"),
                        OffsetDateTime.parse("2026-08-19T12:00:00Z"),
                        OffsetDateTime.parse("2026-08-19T12:30:00Z"));
    }

    @Test
    void collectDue_consecutivePasses_doNotRewriteABucket() {
        StatsCollectionJob job = jobStartedAt("2026-08-19T10:30:00Z");

        job.collectDue(Instant.parse("2026-08-19T11:05:00Z"));
        int second = job.collectDue(Instant.parse("2026-08-19T11:35:00Z"));

        assertThat(second).isEqualTo(1);
        verify(collectionService).collectBucket(OffsetDateTime.parse("2026-08-19T10:30:00Z"));
        verify(collectionService).collectBucket(OffsetDateTime.parse("2026-08-19T11:00:00Z"));
        verifyNoMoreInteractions(collectionService);
    }

    @Test
    void collectDue_longOutage_isCappedSoOnePassCannotRunAway() {
        StatsCollectionJob job = jobStartedAt("2026-08-19T00:00:00Z");

        int written = job.collectDue(Instant.parse("2026-08-25T00:00:00Z"));

        // Six days is 288 buckets; one pass takes 48 and the rest are picked up by later passes.
        assertThat(written).isEqualTo(48);
        verify(collectionService, times(48)).collectBucket(any());
    }
}
