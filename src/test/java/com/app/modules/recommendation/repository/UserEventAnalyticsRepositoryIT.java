package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.clickhouse.ClickHouseContainer;

import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository.AffinitySignal;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository.FeedbackTotal;
import com.app.testsupport.ClickHouseTestSupport;

/**
 * The behavioural event store's reads and writes against a real ClickHouse provisioned the way
 * production is: the window and filters of the activity page, the read-set, the exact sums of the
 * affinity and rebuild reads, and the guarantee that the table's event type set is the enum's.
 */
class UserEventAnalyticsRepositoryIT {

    private static final long HALF_LIFE_SECONDS = 30L * 86_400L;
    private static final OffsetDateTime WINDOW_END =
            OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    static ClickHouseContainer clickhouse;
    static JdbcClient admin;
    static UserEventAnalyticsRepository repository;

    @BeforeAll
    static void start() {
        clickhouse = ClickHouseTestSupport.startProvisioned();
        ClickHouseTestSupport.applyMigrations(clickhouse);
        admin =
                ClickHouseTestSupport.clientAs(
                        clickhouse, clickhouse.getUsername(), clickhouse.getPassword());
        repository =
                new UserEventAnalyticsRepositoryImpl(
                        ClickHouseTestSupport.directOperations(clickhouse));
    }

    @AfterAll
    static void stop() {
        clickhouse.stop();
    }

    @BeforeEach
    void truncate() {
        admin.sql("TRUNCATE TABLE user_events").update();
    }

    private static void insert(
            UUID id, UUID userId, UserEventType type, UUID entityId, OffsetDateTime at) {
        repository.insertImported(
                id, userId, type, entityId == null ? null : "post", entityId, "", at);
    }

    @Test
    void findRecentEntityIds_boundedByWindow_excludesReadsOutsideIt() {
        UUID user = UUID.randomUUID();
        UUID inside = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insert(UUID.randomUUID(), user, UserEventType.POST_VIEW, inside, now.minusDays(5));
        insert(UUID.randomUUID(), user, UserEventType.POST_VIEW, outside, now.minusDays(200));

        List<UUID> ids =
                repository.findRecentEntityIds(
                        user, UserEventType.POST_VIEW, now.minusDays(90), now, 2000);

        assertThat(ids).containsExactly(inside);
    }

    @Test
    void findRecentEntityIds_capsAtLimitNewestFirst() {
        UUID user = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID post = UUID.randomUUID();
            newestFirst.add(post);
            insert(UUID.randomUUID(), user, UserEventType.POST_VIEW, post, now.minusHours(i + 1));
        }

        List<UUID> ids =
                repository.findRecentEntityIds(
                        user, UserEventType.POST_VIEW, now.minusDays(1), now, 3);

        assertThat(ids).containsExactlyElementsOf(newestFirst.subList(0, 3));
    }

    @Test
    void findRecentEntityIds_filtersByEventTypeAndSkipsRowsWithoutAnEntity() {
        UUID user = UUID.randomUUID();
        UUID viewed = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insert(UUID.randomUUID(), user, UserEventType.POST_VIEW, viewed, now.minusHours(1));
        insert(
                UUID.randomUUID(),
                user,
                UserEventType.POST_LIKE,
                UUID.randomUUID(),
                now.minusHours(1));
        insert(UUID.randomUUID(), user, UserEventType.POST_VIEW, null, now.minusHours(2));

        List<UUID> ids =
                repository.findRecentEntityIds(
                        user, UserEventType.POST_VIEW, now.minusDays(1), now, 2000);

        assertThat(ids).containsExactly(viewed);
    }

    @Test
    void findRecentEntityIds_isNotFinal_soAnUnmergedDuplicateRepeatsAndTheCallersSetCollapsesIt() {
        UUID user = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        insert(eventId, user, UserEventType.POST_VIEW, post, at);
        insert(eventId, user, UserEventType.POST_VIEW, post, at);

        List<UUID> ids =
                repository.findRecentEntityIds(
                        user, UserEventType.POST_VIEW, at.minusDays(1), at.plusDays(1), 2000);

        assertThat(ids).containsOnly(post);
        assertThat(new HashSet<>(ids)).hasSize(1);
    }

    @Test
    void findPage_appliesWindowUserAndTypeFilters() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        OffsetDateTime from = WINDOW_END.minusDays(10);
        UUID aliceLogin = UUID.randomUUID();
        insert(aliceLogin, alice, UserEventType.SESSION_START, null, WINDOW_END.minusDays(1));
        insert(
                UUID.randomUUID(),
                alice,
                UserEventType.PROFILE_VIEW,
                UUID.randomUUID(),
                WINDOW_END.minusDays(2));
        insert(UUID.randomUUID(), bob, UserEventType.SESSION_START, null, WINDOW_END.minusDays(3));
        insert(
                UUID.randomUUID(),
                alice,
                UserEventType.SESSION_START,
                null,
                WINDOW_END.minusDays(30));

        List<UserEventRow> everyone =
                repository.findPage(null, from, WINDOW_END, null, null, null, 50);
        List<UserEventRow> aliceOnly =
                repository.findPage(alice, from, WINDOW_END, null, null, null, 50);
        List<UserEventRow> aliceLogins =
                repository.findPage(
                        alice, from, WINDOW_END, UserEventType.SESSION_START, null, null, 50);

        assertThat(everyone).hasSize(3);
        assertThat(aliceOnly).hasSize(2);
        assertThat(aliceLogins).extracting(UserEventRow::id).containsExactly(aliceLogin);
        assertThat(everyone)
                .extracting(UserEventRow::createdAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void findPage_windowIsHalfOpen_includesFromAndExcludesTo() {
        UUID user = UUID.randomUUID();
        UUID atFrom = UUID.randomUUID();
        UUID atTo = UUID.randomUUID();
        OffsetDateTime from = WINDOW_END.minusDays(1);
        insert(atFrom, user, UserEventType.APP_OPEN, null, from);
        insert(atTo, user, UserEventType.APP_OPEN, null, WINDOW_END);

        List<UserEventRow> rows = repository.findPage(user, from, WINDOW_END, null, null, null, 10);

        assertThat(rows).extracting(UserEventRow::id).containsExactly(atFrom);
    }

    @Test
    void findPage_isFinal_soAnUnmergedDuplicateShowsOnce() {
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime at = WINDOW_END.minusHours(3);
        insert(eventId, user, UserEventType.SESSION_START, null, at);
        insert(eventId, user, UserEventType.SESSION_START, null, at);

        List<UserEventRow> rows =
                repository.findPage(user, at.minusDays(1), WINDOW_END, null, null, null, 10);

        assertThat(rows).hasSize(1);
    }

    @Test
    void findPage_returnsMetadataTextAndTheEntityReference() {
        UUID user = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        OffsetDateTime at = WINDOW_END.minusHours(3);
        repository.insertImported(
                UUID.randomUUID(),
                user,
                UserEventType.SEARCH,
                null,
                null,
                "{\"scope\":\"posts\",\"query\":\"sunset\"}",
                at);
        repository.insertImported(
                UUID.randomUUID(),
                user,
                UserEventType.PROFILE_VIEW,
                "user",
                target,
                "",
                at.minusMinutes(1));

        List<UserEventRow> rows =
                repository.findPage(user, at.minusDays(1), WINDOW_END, null, null, null, 10);

        assertThat(rows.get(0).eventType()).isEqualTo(UserEventType.SEARCH);
        assertThat(rows.get(0).metadata()).isEqualTo("{\"scope\":\"posts\",\"query\":\"sunset\"}");
        assertThat(rows.get(1).entityType()).isEqualTo("user");
        assertThat(rows.get(1).entityId()).isEqualTo(target);
        assertThat(rows.get(1).metadata()).isEmpty();
    }

    @Test
    void recordDetached_isVisibleOnceTheAsyncBufferIsFlushed_withAGeneratedId() {
        UUID user = UUID.randomUUID();
        // ClickHouse stores microseconds.
        OffsetDateTime at =
                OffsetDateTime.now(ZoneOffset.UTC)
                        .minusMinutes(1)
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        repository.recordDetached(user, UserEventType.SESSION_START, null, null, "", at);
        admin.sql("SYSTEM FLUSH ASYNC INSERT QUEUE").update();

        List<UserEventRow> rows =
                repository.findPage(user, at.minusDays(1), at.plusDays(1), null, null, null, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isNotNull();
        assertThat(rows.get(0).createdAt().toInstant()).isEqualTo(at.toInstant());
    }

    @Test
    void insertEngagement_storesTheGorseFeedbackItWasSentWith() {
        UUID eventId = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID post = UUID.randomUUID();

        repository.insertEngagement(
                eventId,
                user,
                UserEventType.COMMENT_LIKE,
                "post",
                post,
                WINDOW_END.minusHours(1),
                "like",
                0.5);

        String type =
                admin.sql("SELECT feedback_type FROM user_events WHERE id = :id")
                        .param("id", eventId)
                        .query(String.class)
                        .single();
        double value =
                admin.sql("SELECT feedback_value FROM user_events WHERE id = :id")
                        .param("id", eventId)
                        .query(Double.class)
                        .single();
        assertThat(type).isEqualTo("like");
        assertThat(value).isEqualTo(0.5);
    }

    @Test
    void insertImported_carriesNoFeedback_soARebuildNeverReplaysIt() {
        UUID eventId = UUID.randomUUID();
        repository.insertImported(
                eventId,
                UUID.randomUUID(),
                UserEventType.POST_LIKE,
                "post",
                UUID.randomUUID(),
                "",
                WINDOW_END.minusHours(1));

        Long withFeedback =
                admin.sql("SELECT count() FROM user_events WHERE feedback_type IS NOT NULL")
                        .query(Long.class)
                        .single();
        assertThat(withFeedback).isZero();
    }

    @Test
    void everyUserEventType_canBeInserted_andTheTablesTypeSetIsTheEnums() {
        UUID user = UUID.randomUUID();
        for (UserEventType type : UserEventType.values()) {
            insert(UUID.randomUUID(), user, type, null, WINDOW_END.minusHours(1));
        }

        Long distinct =
                admin.sql("SELECT uniqExact(event_type) FROM user_events")
                        .query(Long.class)
                        .single();
        assertThat(distinct).isEqualTo(UserEventType.values().length);

        String columnType =
                admin.sql(
                                "SELECT type FROM system.columns WHERE database ="
                                        + " 'luvax_analytics' AND table = 'user_events' AND name ="
                                        + " 'event_type'")
                        .query(String.class)
                        .single();
        Set<String> declared = new HashSet<>();
        Matcher matcher = Pattern.compile("'([a-z_]+)' = \\d+").matcher(columnType);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        Set<String> expected = new HashSet<>();
        for (UserEventType type : UserEventType.values()) {
            expected.add(type.toJson());
        }
        assertThat(declared).isEqualTo(expected);
    }

    @Test
    void streamAffinitySignals_weighsAndDecaysEachEventAndCancelsAReversal() {
        UUID user = UUID.randomUUID();
        UUID likedPost = UUID.randomUUID();
        UUID cancelledPost = UUID.randomUUID();
        UUID hashtag = UUID.randomUUID();
        OffsetDateTime oneHalfLifeAgo = WINDOW_END.minusSeconds(HALF_LIFE_SECONDS);
        // A like one half-life old is worth its weight, 2.0, halved.
        insert(UUID.randomUUID(), user, UserEventType.POST_LIKE, likedPost, oneHalfLifeAgo);
        // A like and its unlike at the same instant sum to nothing.
        insert(UUID.randomUUID(), user, UserEventType.POST_LIKE, cancelledPost, oneHalfLifeAgo);
        insert(UUID.randomUUID(), user, UserEventType.POST_UNLIKE, cancelledPost, oneHalfLifeAgo);
        // A hashtag click carries its own weight, 3.0, and targets the hashtag directly.
        repository.insertImported(
                UUID.randomUUID(),
                user,
                UserEventType.HASHTAG_CLICK,
                "hashtag",
                hashtag,
                "",
                oneHalfLifeAgo);
        // Outside the window, and of a type that carries no weight: neither contributes.
        insert(
                UUID.randomUUID(),
                user,
                UserEventType.POST_SAVE,
                UUID.randomUUID(),
                WINDOW_END.minusDays(120));
        insert(UUID.randomUUID(), user, UserEventType.APP_OPEN, null, oneHalfLifeAgo);

        List<AffinitySignal> signals = new ArrayList<>();
        repository.streamAffinitySignals(
                WINDOW_END.minusDays(90), WINDOW_END, HALF_LIFE_SECONDS, 2, signals::addAll);

        assertThat(signals).hasSize(3);
        assertThat(signal(signals, likedPost).contribution()).isCloseTo(1.0, within(1e-9));
        assertThat(signal(signals, likedPost).targetKind()).isEqualTo("post");
        assertThat(signal(signals, likedPost).eventCount()).isEqualTo(1);
        assertThat(signal(signals, cancelledPost).contribution()).isCloseTo(0.0, within(1e-9));
        assertThat(signal(signals, cancelledPost).eventCount()).isEqualTo(2);
        assertThat(signal(signals, hashtag).contribution()).isCloseTo(1.5, within(1e-9));
        assertThat(signal(signals, hashtag).targetKind()).isEqualTo("hashtag");
    }

    @Test
    void streamAffinitySignals_deliversInBatchesOfTheRequestedSize() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            insert(
                    UUID.randomUUID(),
                    user,
                    UserEventType.POST_LIKE,
                    UUID.randomUUID(),
                    WINDOW_END.minusDays(1));
        }

        List<Integer> batchSizes = new ArrayList<>();
        repository.streamAffinitySignals(
                WINDOW_END.minusDays(90),
                WINDOW_END,
                HALF_LIFE_SECONDS,
                2,
                batch -> batchSizes.add(batch.size()));

        assertThat(batchSizes).containsExactly(2, 2, 1);
    }

    @Test
    void streamAffinitySignals_aFailureInTheSink_isRethrownAsItselfNotAsAClickHouseFailure() {
        insert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UserEventType.POST_LIKE,
                UUID.randomUUID(),
                WINDOW_END.minusDays(1));
        IllegalStateException sinkFailure = new IllegalStateException("staging failed");

        assertThatThrownBy(
                        () ->
                                repository.streamAffinitySignals(
                                        WINDOW_END.minusDays(90),
                                        WINDOW_END,
                                        HALF_LIFE_SECONDS,
                                        10,
                                        batch -> {
                                            throw sinkFailure;
                                        }))
                .isSameAs(sinkFailure);
    }

    @Test
    void findFeedbackTotals_sumsPerTypeAndIgnoresRowsThatWereNeverSent() {
        UUID user = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        OffsetDateTime first = WINDOW_END.minusHours(2);
        OffsetDateTime second = WINDOW_END.minusHours(1);
        repository.insertEngagement(
                UUID.randomUUID(),
                user,
                UserEventType.POST_VIEW,
                "post",
                post,
                first,
                "read",
                12.0);
        repository.insertEngagement(
                UUID.randomUUID(),
                user,
                UserEventType.POST_VIEW,
                "post",
                post,
                second,
                "read",
                8.0);
        repository.insertEngagement(
                UUID.randomUUID(), user, UserEventType.POST_LIKE, "post", post, first, "like", 1.0);
        // A row that was never sent to Gorse carries no feedback type and is not counted.
        insert(UUID.randomUUID(), user, UserEventType.POST_SAVE, post, first);
        // Another account is outside the batch asked for.
        repository.insertEngagement(
                UUID.randomUUID(),
                otherUser,
                UserEventType.POST_LIKE,
                "post",
                post,
                first,
                "like",
                1.0);

        List<FeedbackTotal> totals = repository.findFeedbackTotals(List.of(user));

        assertThat(totals).hasSize(2);
        FeedbackTotal read =
                totals.stream()
                        .filter(t -> t.feedbackType().equals("read"))
                        .findFirst()
                        .orElseThrow();
        assertThat(read.value()).isEqualTo(20.0);
        assertThat(read.userId()).isEqualTo(user);
        assertThat(read.itemId()).isEqualTo(post);
        assertThat(read.latest().toInstant()).isEqualTo(second.toInstant());
        assertThat(totals)
                .extracting(FeedbackTotal::feedbackType)
                .containsExactlyInAnyOrder("read", "like");
    }

    @Test
    void findFeedbackTotals_isFinal_soARedeliveredEventIsCountedOnce() {
        UUID user = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            repository.insertEngagement(
                    eventId,
                    user,
                    UserEventType.POST_VIEW,
                    "post",
                    post,
                    WINDOW_END.minusHours(1),
                    "read",
                    5.0);
        }

        List<FeedbackTotal> totals = repository.findFeedbackTotals(List.of(user));

        assertThat(totals).hasSize(1);
        assertThat(totals.get(0).value()).isEqualTo(5.0);
    }

    @Test
    void findFeedbackTotals_noUsers_readsNothing() {
        assertThat(repository.findFeedbackTotals(List.of())).isEmpty();
    }

    private static AffinitySignal signal(List<AffinitySignal> signals, UUID target) {
        return signals.stream().filter(s -> s.targetId().equals(target)).findFirst().orElseThrow();
    }
}
