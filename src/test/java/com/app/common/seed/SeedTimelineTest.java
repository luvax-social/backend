package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.app.common.seed.model.MessageSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.time.SeedTimeline;

class SeedTimelineTest {

    private static final long FIXED_SEED = 20260825L;
    private static final ZoneId SEED_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void commentCreatedAt_neverPrecedesItsPost() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        Instant postCreatedAt = Instant.parse("2026-01-15T10:00:00Z");
        for (int i = 0; i < 1000; i++) {
            Instant commentCreatedAt = timeline.commentCreatedAt(postCreatedAt);
            assertThat(commentCreatedAt).isAfterOrEqualTo(postCreatedAt);
        }
    }

    @Test
    void sameSeedProducesIdenticalTimestamps() {
        SeedTimeline first = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        SeedTimeline second = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        Instant postCreatedAt = Instant.parse("2026-01-15T10:00:00Z");
        assertThat(first.commentCreatedAt(postCreatedAt))
                .isEqualTo(second.commentCreatedAt(postCreatedAt));
    }

    @Test
    void messageCreatedAt_preservesScriptedTurnOrder() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        Instant conversationCreatedAt = Instant.parse("2026-08-20T09:00:00Z");
        List<Integer> scriptedOffsetsMinutes = List.of(0, 1, 2, 5, 10, 25, 42, 100, 250);

        Instant previous = null;
        for (int offsetMinutes : scriptedOffsetsMinutes) {
            MessageSeed message =
                    new MessageSeed(
                            "alice", "hi", "text", offsetMinutes, null, false, null, null, null);
            Instant createdAt = timeline.messageCreatedAt(message, conversationCreatedAt);
            assertThat(createdAt).isAfterOrEqualTo(conversationCreatedAt);
            if (previous != null) {
                // Every pair of scripted offsets above differs by at least 1 minute, so the
                // resulting timestamps must come back strictly increasing, never inverted by
                // jitter.
                assertThat(createdAt).isAfter(previous);
            }
            previous = createdAt;
        }
    }

    @Test
    void messageCreatedAt_sameSeedProducesIdenticalTimestampForSameOffset() {
        Instant conversationCreatedAt = Instant.parse("2026-08-20T09:00:00Z");
        MessageSeed message =
                new MessageSeed("alice", "hi", "text", 42, null, false, null, null, null);

        SeedTimeline first = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        SeedTimeline second = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));

        assertThat(first.messageCreatedAt(message, conversationCreatedAt))
                .isEqualTo(second.messageCreatedAt(message, conversationCreatedAt));
    }

    @Test
    void postCreatedAt_weekdayEveningHint_landsInEveningWindowOnAWeekday() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        PostSeed post = postWithTimeHint("weekday_evening");

        for (int i = 0; i < 500; i++) {
            Instant createdAt = timeline.postCreatedAt(post);
            ZonedDateTime local = createdAt.atZone(SEED_ZONE);
            assertThat(local.getHour()).isBetween(19, 21);
            assertThat(local.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
    }

    @Test
    void postCreatedAt_weekendMorningHint_landsInMorningWindowOnAWeekend() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        PostSeed post = postWithTimeHint("weekend_morning");

        for (int i = 0; i < 500; i++) {
            Instant createdAt = timeline.postCreatedAt(post);
            ZonedDateTime local = createdAt.atZone(SEED_ZONE);
            assertThat(local.getHour()).isBetween(8, 10);
            assertThat(local.getDayOfWeek()).isIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
    }

    @Test
    void postCreatedAt_noTimeHint_rarelyLandsInTheNearZeroOvernightWindow() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-08-25T00:00:00Z"));
        PostSeed post = postWithTimeHint(null);

        int sampleCount = 3000;
        long overnightCount =
                java.util.stream.IntStream.range(0, sampleCount)
                        .mapToObj(i -> timeline.postCreatedAt(post).atZone(SEED_ZONE).getHour())
                        .filter(hour -> hour >= 2 && hour < 6)
                        .count();

        // DEFAULT_HOUR_WEIGHTS gives hours 2-5 a combined ~4.5% share of the total weight; a
        // generous 15% ceiling leaves ample margin for sampling noise while still proving the
        // near-zero overnight window is not being sampled as if uniform (uniform would be ~16.7%).
        assertThat((double) overnightCount / sampleCount).isLessThan(0.15);
    }

    @Test
    void supportTicketCreatedAt_returnsInstantOnOrAfterUserCreatedAt() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-09-01T00:00:00Z"));
        Instant userCreatedAt = Instant.parse("2026-01-01T00:00:00Z");

        Instant result = timeline.supportTicketCreatedAt(userCreatedAt);

        assertThat(result).isAfterOrEqualTo(userCreatedAt);
        assertThat(result).isBeforeOrEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void supportTicketDecidedAt_returnsInstantStrictlyAfterTicketCreatedAt() {
        SeedTimeline timeline = new SeedTimeline(FIXED_SEED, Instant.parse("2026-09-01T00:00:00Z"));
        Instant ticketCreatedAt = Instant.parse("2026-08-01T00:00:00Z");

        Instant result = timeline.supportTicketDecidedAt(ticketCreatedAt);

        assertThat(result).isAfter(ticketCreatedAt);
    }

    private PostSeed postWithTimeHint(String timeHint) {
        return new PostSeed(
                "post_test",
                "author",
                "caption",
                "image",
                List.of(),
                List.of(),
                -1000,
                "published",
                "medium",
                List.of(),
                false,
                null,
                timeHint);
    }
}
