package com.app.common.seed.time;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;

import com.app.common.seed.model.MessageSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.UserSeed;

/**
 * Generates deterministic, causally-ordered {@code created_at} timestamps for every seed writer.
 *
 * <p>Constructed once per seed run with a fixed RNG seed and a single {@code referenceNow} captured
 * at run start; every offset is derived from that one instant, never from {@code Instant.now()}, so
 * two runs against the same seed content produce identical relative timestamps - {@code
 * referenceNow} itself is real wall-clock time and varies run to run, so absolute timestamps are
 * not reproducible, only each generated instant's offset from that reference. Every method builds
 * its result by adding a strictly-positive (or strictly-bounded) random duration on top of the
 * invariant it must respect, so an ordering invariant (e.g. "a comment never precedes its post")
 * holds by construction and can never be violated by clamping a generated value after the fact.
 */
public class SeedTimeline {

    private static final ZoneId SEED_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration MAX_REACTION_GAP = Duration.ofDays(14);
    private static final Duration MAX_FOLLOW_GAP = Duration.ofDays(60);
    private static final Duration MAX_ADMIN_ACTION_GAP = Duration.ofDays(7);
    private static final Duration MAX_SUPPORT_TICKET_AGE = Duration.ofDays(180);
    private static final Duration MAX_SUPPORT_DECISION_GAP = Duration.ofDays(5);
    private static final Duration LIVE_STORY_WINDOW = Duration.ofHours(24);
    private static final Duration EXPIRED_STORY_MIN_AGE = Duration.ofHours(24);
    private static final Duration EXPIRED_STORY_MAX_AGE = Duration.ofHours(72);

    // A message's scripted position within its conversation is expressed in whole minutes and the
    // real data's smallest positive gap between consecutive messages is 1 minute, so a sub-60s
    // jitter can never invert two messages whose scripted offsets actually differ.
    private static final int MESSAGE_JITTER_SECONDS_BOUND = 30;

    // Approximates each Task-2 time_hint value as a day-of-week constraint (null = either) plus an
    // hour-of-day window; the exact clock minute/second within that window is still randomized.
    private static final Map<String, HourWindow> TIME_HINT_WINDOWS =
            Map.of(
                    "weekday_morning", new HourWindow(false, 7, 10),
                    "weekday_lunch", new HourWindow(false, 11, 13),
                    "weekday_afternoon", new HourWindow(false, 13, 17),
                    "weekday_evening", new HourWindow(false, 19, 22),
                    "weekday_late_night", new HourWindow(false, 22, 24),
                    "weekend_morning", new HourWindow(true, 8, 11),
                    "weekend_afternoon", new HourWindow(true, 12, 17));

    // Per-hour sampling weights used when a post carries no time_hint (~72% of posts.json): heavily
    // favors the 19:00-23:00 evening peak and the 11:00-13:00 lunch peak, keeps 02:00-06:00 near
    // zero, per the seed content's authored posting-rhythm principle.
    private static final int[] DEFAULT_HOUR_WEIGHTS = {
        2, 1, 1, 1, 1, 1, 2, 3, 3, 3, 3, 5, 6, 5, 3, 3, 3, 4, 5, 7, 8, 8, 7, 4
    };

    private final Random random;
    private final Instant referenceNow;
    private final Duration maxLookback;

    public SeedTimeline(long seed, Instant referenceNow) {
        this.random = new Random(seed);
        this.referenceNow = referenceNow;
        // Calendar-months-back in the seed timezone, not a fixed 365-day duration, so the window
        // boundary lands on the same wall-clock day of month referenceNow does.
        Instant twelveMonthsBack = referenceNow.atZone(SEED_ZONE).minusMonths(12).toInstant();
        this.maxLookback = Duration.between(twelveMonthsBack, referenceNow);
    }

    /**
     * Returns a timestamp for a post: a day uniformly distributed within the seed run's 12-month
     * lookback window ending at {@code referenceNow} (always a full calendar day before {@code
     * referenceNow}'s date, so the composed instant can never land after it), combined with a
     * time-of-day biased by {@link PostSeed#timeHint()} when present, or by {@link
     * #DEFAULT_HOUR_WEIGHTS} otherwise. A {@code time_hint} of {@code weekday_*}/{@code weekend_*}
     * additionally constrains which day-of-week the post can land on.
     */
    public Instant postCreatedAt(PostSeed post) {
        // Reserves a 7-day margin below the window ceiling so alignToDayOfWeek's forward scan can
        // never get capped against the boundary and stall on a single, possibly-wrong day-of-week.
        int windowDays = Math.max((int) maxLookback.toDays(), 9);
        int dayOffset = 1 + random.nextInt(windowDays - 7);
        // Map.of(...).get(null) throws NPE rather than returning null (~72% of posts.json carries
        // no time_hint), so the null case must short-circuit before the lookup.
        HourWindow hint = post.timeHint() == null ? null : TIME_HINT_WINDOWS.get(post.timeHint());
        if (hint != null && hint.requiresWeekend() != null) {
            dayOffset = alignToDayOfWeek(dayOffset, windowDays, hint.requiresWeekend());
        }
        ZonedDateTime day =
                referenceNow.atZone(SEED_ZONE).minusDays(dayOffset).truncatedTo(ChronoUnit.DAYS);
        int hour = hint != null ? hint.randomHour(random) : weightedRandomHour();
        return day.plusHours(hour)
                .plusMinutes(random.nextInt(60))
                .plusSeconds(random.nextInt(60))
                .toInstant();
    }

    // Shifts dayOffset older (never toward referenceNow) by up to 6 days until it lands on a day
    // matching the required weekday/weekend-ness, capped at the window's oldest day so it can never
    // fall outside the seed run's declared 12-month lookback.
    private int alignToDayOfWeek(int dayOffset, int windowDays, boolean requiresWeekend) {
        for (int shift = 0; shift < 7; shift++) {
            int candidateOffset = Math.min(dayOffset + shift, windowDays - 1);
            DayOfWeek candidateDay =
                    referenceNow.atZone(SEED_ZONE).minusDays(candidateOffset).getDayOfWeek();
            boolean isWeekend =
                    candidateDay == DayOfWeek.SATURDAY || candidateDay == DayOfWeek.SUNDAY;
            if (isWeekend == requiresWeekend) {
                return candidateOffset;
            }
        }
        return dayOffset;
    }

    private int weightedRandomHour() {
        int totalWeight = 0;
        for (int weight : DEFAULT_HOUR_WEIGHTS) {
            totalWeight += weight;
        }
        int pick = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int hour = 0; hour < DEFAULT_HOUR_WEIGHTS.length; hour++) {
            cumulative += DEFAULT_HOUR_WEIGHTS[hour];
            if (pick < cumulative) {
                return hour;
            }
        }
        return DEFAULT_HOUR_WEIGHTS.length - 1;
    }

    private record HourWindow(
            Boolean requiresWeekend, int startHourInclusive, int endHourExclusive) {
        int randomHour(Random random) {
            int span = Math.max(endHourExclusive - startHourInclusive, 1);
            return startHourInclusive + random.nextInt(span);
        }
    }

    /**
     * Returns a timestamp strictly after {@code postCreatedAt}, so a comment can never precede the
     * post it belongs to.
     */
    public Instant commentCreatedAt(Instant postCreatedAt) {
        return postCreatedAt.plus(
                strictlyPositiveRandomDuration(gapUpperBound(postCreatedAt, MAX_REACTION_GAP)));
    }

    /**
     * Returns a timestamp strictly after {@code targetCreatedAt}, used for likes and saves, which
     * can never predate the post or comment they target.
     */
    public Instant likeOrSaveCreatedAt(Instant targetCreatedAt) {
        return targetCreatedAt.plus(
                strictlyPositiveRandomDuration(gapUpperBound(targetCreatedAt, MAX_REACTION_GAP)));
    }

    /**
     * Returns a timestamp on or after the later of two participants' account creation times, so a
     * conversation can never predate either participant's account. Shares {@link
     * #followCreatedAt}'s construction exactly (the invariant is identical: a relationship row can
     * never precede the accounts it connects), kept as its own named method because "when was this
     * conversation started" is a distinct seed concern from "when was this follow edge created".
     */
    public Instant conversationCreatedAt(
            Instant participantACreatedAt, Instant participantBCreatedAt) {
        return followCreatedAt(participantACreatedAt, participantBCreatedAt);
    }

    /**
     * Returns a timestamp derived from {@code message}'s scripted {@code
     * offsetMinutesFromConversationStart}, so a message never predates the conversation it belongs
     * to and, critically, so two messages in the same conversation come back in the same relative
     * order their scripted offsets encode. A sub-minute random jitter is layered on top for
     * realism; it is bounded well under the real data's smallest positive inter-message gap (1
     * minute) so it can never invert two messages whose scripted offsets actually differ.
     */
    public Instant messageCreatedAt(MessageSeed message, Instant conversationCreatedAt) {
        Instant scripted =
                conversationCreatedAt.plusSeconds(
                        message.offsetMinutesFromConversationStart() * 60L);
        Duration remaining = Duration.between(scripted, referenceNow);
        if (remaining.isNegative() || remaining.isZero()) {
            // Scripted offset already reaches referenceNow (only possible if conversationCreatedAt
            // was generated implausibly close to referenceNow) - clamp rather than overshoot it.
            return referenceNow;
        }
        long jitterBoundSeconds = Math.min(MESSAGE_JITTER_SECONDS_BOUND, remaining.getSeconds());
        long jitterSeconds =
                jitterBoundSeconds <= 0 ? 0 : random.nextInt((int) jitterBoundSeconds + 1);
        return scripted.plusSeconds(jitterSeconds);
    }

    /**
     * Returns a timestamp strictly after {@code latestSubtreeActivity}, used when a comment subtree
     * is chosen for a seeded soft-delete: the deletion instant can never predate the newest comment
     * or reply already inside that subtree. Shares {@link #commentCreatedAt}'s and {@link
     * #likeOrSaveCreatedAt}'s construction (a strictly-positive offset bounded by the same {@link
     * #MAX_REACTION_GAP}), kept as its own named method because "when was this subtree deleted" is
     * a distinct seed concern from "when was this comment created", even though the arithmetic is
     * identical.
     */
    public Instant commentDeletedAt(Instant latestSubtreeActivity) {
        return latestSubtreeActivity.plus(
                strictlyPositiveRandomDuration(
                        gapUpperBound(latestSubtreeActivity, MAX_REACTION_GAP)));
    }

    /**
     * Returns a timestamp for a user account: {@code referenceNow} shifted by {@link
     * UserSeed#createdAtOffsetDays()} days (always negative or zero in seed content, so the result
     * always lands on or before {@code referenceNow}), with a random time-of-day layered on top so
     * accounts created on the same scripted day do not all share one instant.
     */
    public Instant userCreatedAt(UserSeed user) {
        ZonedDateTime day =
                referenceNow
                        .atZone(SEED_ZONE)
                        .plusDays(user.createdAtOffsetDays())
                        .truncatedTo(ChronoUnit.DAYS);
        return day.plusHours(random.nextInt(24))
                .plusMinutes(random.nextInt(60))
                .plusSeconds(random.nextInt(60))
                .toInstant();
    }

    /**
     * Returns a timestamp on or after the later of the two accounts' creation times, so a follow
     * edge can never predate either account it connects.
     */
    public Instant followCreatedAt(Instant followerCreatedAt, Instant followingCreatedAt) {
        Instant laterAccountCreatedAt =
                followerCreatedAt.isAfter(followingCreatedAt)
                        ? followerCreatedAt
                        : followingCreatedAt;
        return laterAccountCreatedAt.plus(
                strictlyPositiveRandomDuration(
                        gapUpperBound(laterAccountCreatedAt, MAX_FOLLOW_GAP)));
    }

    /**
     * Returns a timestamp strictly after {@code reportCreatedAt}, so an admin action can never
     * predate the report that triggered it.
     */
    Instant adminActionCreatedAt(Instant reportCreatedAt) {
        return reportCreatedAt.plus(
                strictlyPositiveRandomDuration(
                        gapUpperBound(reportCreatedAt, MAX_ADMIN_ACTION_GAP)));
    }

    /**
     * Returns a timestamp within the last 24 hours when {@code shouldBeLive} is {@code true} (still
     * within a story's visibility window), or one 24-72 hours in the past otherwise (recently
     * expired but not yet purged by the cleanup job).
     */
    public Instant storyCreatedAt(boolean shouldBeLive) {
        if (shouldBeLive) {
            return referenceNow.minus(boundedRandomDuration(LIVE_STORY_WINDOW));
        }
        Duration extraAge =
                boundedRandomDuration(EXPIRED_STORY_MAX_AGE.minus(EXPIRED_STORY_MIN_AGE));
        return referenceNow.minus(EXPIRED_STORY_MIN_AGE).minus(extraAge);
    }

    /**
     * Returns the single {@code referenceNow} instant every generator in this class is anchored to,
     * for a caller (like {@link AnalyticsSeedWriter}) that must lay out bucket boundaries relative
     * to the same run-wide "now" rather than {@code Instant.now()}.
     */
    public Instant referenceNow() {
        return referenceNow;
    }

    /**
     * Returns a timestamp for a support ticket: on or after the account's own creation time,
     * bounded to a realistic recency window so the whole ticket population is not clustered at
     * account creation.
     */
    public Instant supportTicketCreatedAt(Instant userCreatedAt) {
        return userCreatedAt.plus(
                strictlyPositiveRandomDuration(
                        gapUpperBound(userCreatedAt, MAX_SUPPORT_TICKET_AGE)));
    }

    /**
     * Returns a timestamp strictly after {@code ticketCreatedAt}, used for a claim, an escalation,
     * or a final decision on a support ticket - none of which can predate the ticket they act on.
     */
    public Instant supportTicketDecidedAt(Instant ticketCreatedAt) {
        return ticketCreatedAt.plus(
                strictlyPositiveRandomDuration(
                        gapUpperBound(ticketCreatedAt, MAX_SUPPORT_DECISION_GAP)));
    }

    /**
     * Returns a timestamp for a {@code user_events} row, uniformly distributed within the last
     * {@code lookbackDays} days ending at {@code referenceNow} (never after it), matching the same
     * always-in-the-past guarantee every other generator in this class gives.
     */
    public Instant userEventCreatedAt(int lookbackDays) {
        long maxSeconds = Duration.ofDays(lookbackDays).getSeconds();
        long offsetSeconds = (long) (random.nextDouble() * maxSeconds);
        return referenceNow.minusSeconds(offsetSeconds);
    }

    /**
     * Returns a start instant for a pre-authored moderation-case timeline (one of {@code
     * moderation_cases.json}'s cases, or the supplementary actions/reports lists treated as one
     * combined pseudo-case) whose original entries span {@code totalSpan} from first event to last.
     *
     * <p>The caller re-applies each original event's own offset from the timeline's first event on
     * top of the returned start, so every event's relative pacing survives exactly while the whole
     * timeline is relocated into the seed run's real timeframe. {@code start + totalSpan} never
     * exceeds {@code referenceNow}, the same always-in-the-past guarantee {@link #postCreatedAt}
     * gives a post, which is what makes "an admin_actions row is never earlier than the report that
     * triggered it" hold by construction: a pure translation preserves the original ordering.
     */
    public Instant moderationCaseStart(Duration totalSpan) {
        Duration budget = maxLookback.minus(totalSpan);
        if (budget.isNegative()) {
            budget = Duration.ZERO;
        }
        long maxSeconds = budget.getSeconds();
        long offsetSeconds = maxSeconds <= 0 ? 0 : (long) (random.nextDouble() * maxSeconds);
        return referenceNow.minus(totalSpan).minusSeconds(offsetSeconds);
    }

    /**
     * Returns a start instant for a moderation-case timeline the same way {@link
     * #moderationCaseStart} does, except the timeline carries one "still active as of {@code
     * referenceNow}" anchor event (e.g. a {@code suspend_user} action whose fixed-term expiry the
     * case's own outcome says has not yet passed) that {@code moderationCaseStart} cannot guarantee
     * on its own.
     *
     * <p>{@code offsetToActiveEvent} is the anchor event's offset from the timeline's first event;
     * {@code activeDuration} is how long the state stays active after the anchor (e.g. the
     * suspension's {@code duration_days}). The anchor event's own generated instant always lands in
     * ({@code referenceNow - activeDuration}, {@code referenceNow}], so adding {@code
     * activeDuration} back always lands strictly after {@code referenceNow} - the expiry is in the
     * future by construction, never by clamping a computed expiry forward after the fact.
     */
    public Instant moderationCaseStartWithActiveAnchor(
            Duration offsetToActiveEvent, Duration activeDuration) {
        long activeDurationSeconds = Math.max(activeDuration.getSeconds(), 1);
        // Strictly less than activeDurationSeconds, so anchor + activeDuration always overshoots
        // referenceNow rather than landing exactly on it.
        long jitterSeconds =
                activeDurationSeconds <= 1 ? 0 : random.nextInt((int) (activeDurationSeconds - 1));
        Instant anchorInstant = referenceNow.minusSeconds(jitterSeconds);
        return anchorInstant.minus(offsetToActiveEvent);
    }

    // Keeps the added gap close to referenceNow when base is already at or past it; the 1-second
    // floor below means a base exactly equal to referenceNow overshoots it by up to 1 second, an
    // accepted measure-zero edge case rather than one this method eliminates.
    private Duration gapUpperBound(Instant base, Duration preferredMax) {
        Duration remaining = Duration.between(base, referenceNow);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofSeconds(1);
        }
        return remaining.compareTo(preferredMax) < 0 ? remaining : preferredMax;
    }

    private Duration boundedRandomDuration(Duration max) {
        long maxSeconds = Math.max(max.getSeconds(), 1);
        long offsetSeconds = (long) (random.nextDouble() * maxSeconds);
        return Duration.ofSeconds(offsetSeconds);
    }

    // Always at least one second, so the result is strictly greater than base, never equal to it.
    private Duration strictlyPositiveRandomDuration(Duration max) {
        long maxSeconds = Math.max(max.getSeconds(), 1);
        long offsetSeconds = 1 + (long) (random.nextDouble() * (maxSeconds - 1));
        return Duration.ofSeconds(offsetSeconds);
    }
}
