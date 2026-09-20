package com.app.common.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;
import com.app.common.seed.outbox.SeedOutboxEmitter;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.AnalyticsSeedWriter;
import com.app.common.seed.writer.CommentSeedWriter;
import com.app.common.seed.writer.EngagementSeedWriter;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.MessageSeedWriter;
import com.app.common.seed.writer.ModerationSeedWriter;
import com.app.common.seed.writer.NotificationSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.SocialGraphSeedWriter;
import com.app.common.seed.writer.StorySeedWriter;
import com.app.common.seed.writer.SupportSeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.common.seed.writer.VerificationSeedWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point that orchestrates the full seed pipeline on application startup: wipes every seedable
 * table, runs every domain writer from Tasks 5-7 in FK-safe order, emits the full seed event volume
 * through the real transactional outbox, then asserts every enum-typed column this pipeline is
 * supposed to exercise actually reaches a minimum row count per value.
 *
 * <p>Active under {@code dev} with {@code seed}, exactly as originally gated, and now also under
 * {@code prod} with {@code seed} - accepted for this project's staging-style deployment model,
 * where the {@code prod}-profiled environment is a disposable demo target rather than a system
 * serving real users. {@code SEED_DATA=true} must also be set. Work runs on a daemon thread off
 * {@link ApplicationReadyEvent} so it never blocks the application from reporting ready.
 */
@Slf4j
@Component
@Profile("seed & (dev | prod)")
@ConditionalOnProperty(name = "SEED_DATA", havingValue = "true")
@EnableConfigurationProperties(SeedProperties.class)
@RequiredArgsConstructor
public class SeedRunner {

    // The 16 enum-typed columns the plan names as the mandatory coverage set, paired with the
    // table each lives on. Every value of every one of these enums must appear on at least
    // MIN_ROWS_PER_ENUM_VALUE rows once a full seed run completes.
    private static final List<EnumColumn> ENUM_COLUMNS =
            List.of(
                    new EnumColumn("users", "status", "user_status"),
                    new EnumColumn("users", "role", "user_role"),
                    new EnumColumn("posts", "status", "post_status"),
                    new EnumColumn("posts", "post_type", "post_type"),
                    new EnumColumn("media_assets", "media_type", "media_type"),
                    new EnumColumn("follows", "status", "follow_status"),
                    new EnumColumn("hashtags", "status", "hashtag_status"),
                    new EnumColumn("stories", "story_type", "story_type"),
                    new EnumColumn("messages", "message_type", "message_type"),
                    new EnumColumn("reports", "report_type", "report_type"),
                    new EnumColumn("reports", "status", "report_status"),
                    new EnumColumn("reports", "report_reason", "report_reason"),
                    new EnumColumn("notifications", "type", "notification_type"),
                    new EnumColumn("admin_actions", "action_type", "admin_action_type"),
                    new EnumColumn("user_events", "event_type", "event_type"),
                    new EnumColumn("platform_stats", "granularity", "stat_granularity"));

    private static final int MIN_ROWS_PER_ENUM_VALUE = 5;

    // user_events.event_type = 'post_view' is produced by the recommendation consumer draining
    // post.viewed.v1 from the outbox, which happens asynchronously after this run returns. The
    // assertion below reads the database synchronously, so it would always see zero and always
    // fail. Coverage for this one value is asserted against the drained system instead.
    private static final Map<String, Set<String>> ASYNC_ENUM_VALUES =
            Map.of("user_events.event_type", Set.of("post_view"));

    private final JdbcTemplate jdbc;
    private final SeedResetService resetService;
    private final UserSeedWriter userSeedWriter;
    private final MediaSeedWriter mediaSeedWriter;
    private final PostSeedWriter postSeedWriter;
    private final CommentSeedWriter commentSeedWriter;
    private final EngagementSeedWriter engagementSeedWriter;
    private final SocialGraphSeedWriter socialGraphSeedWriter;
    private final StorySeedWriter storySeedWriter;
    private final MessageSeedWriter messageSeedWriter;
    private final NotificationSeedWriter notificationSeedWriter;
    private final ModerationSeedWriter moderationSeedWriter;
    private final SupportSeedWriter supportSeedWriter;
    private final VerificationSeedWriter verificationSeedWriter;
    private final AnalyticsSeedWriter analyticsSeedWriter;
    private final SeedOutboxEmitter seedOutboxEmitter;
    private final SeedProperties seedProperties;
    private final Environment environment;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    /**
     * Fails application startup outright when {@code SEED_DATA=true} (the only way this bean exists
     * at all) but the {@code seed} profile is not active.
     *
     * <p>Without the {@code seed} profile, {@code application-seed.yml} never applies, so the five
     * notification-producing consumers stay live through the whole run and stamp every seeded
     * notification event with {@code NOW()} while {@code NotificationSeedWriter} is also writing
     * historically-dated rows for the same events - the exact condition this check exists to make
     * impossible rather than merely documented.
     *
     * @throws IllegalStateException naming the exact command to use instead
     */
    @PostConstruct
    void assertSeedProfileActive() {
        boolean seedProfileActive =
                java.util.Arrays.asList(environment.getActiveProfiles()).contains("seed");
        if (!seedProfileActive) {
            throw new IllegalStateException(
                    "SEED_DATA=true requires the 'seed' profile to also be active, with 'seed'"
                            + " listed after 'dev' so its overrides win. Start with"
                            + " SPRING_PROFILES_ACTIVE=dev,seed SEED_DATA=true instead. If you set"
                            + " SPRING_PROFILES_ACTIVE in the .env file, that has no effect here:"
                            + " Spring Boot resolves active profiles before spring.config.import"
                            + " (which is how .env is loaded) is applied, so profile selection must"
                            + " come from a real environment variable, a JVM/Maven system property,"
                            + " or a command-line argument - never from .env.");
        }
    }

    // A JVM system property, not a Spring bean field: DevTools reloads every one of this
    // application's own classes (including a fresh SeedRunner instance) from its "restart"
    // classloader on each hot restart, so an instance field or static field on this class is
    // reset every time regardless. A system property lives on java.lang.System, loaded once by
    // the base classloader for the life of the JVM process, so it is the one place a flag
    // actually survives a DevTools restart within the same process - confirmed empirically: the
    // thread name is "restartedMain" on the very first boot too (DevTools always launches through
    // its restart-capable thread, not only on a genuine hot reload), so thread name cannot tell
    // the two apart, but this property can.
    private static final String ALREADY_RAN_PROPERTY = "app.seed.already-ran-this-jvm";

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (System.getProperty(ALREADY_RAN_PROPERTY) != null) {
            log.warn(
                    "[seed] skipping reseed: this JVM process already ran the seed once (this is"
                            + " a devtools hot restart, not a fresh process). Stop and restart the"
                            + " application to force a reseed.");
            return;
        }
        System.setProperty(ALREADY_RAN_PROPERTY, "true");
        Thread worker = new Thread(this::runSeed, "seed-runner");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Refuses to proceed unless the given JDBC URL is provably local (localhost, 127.0.0.1 or the
     * IPv6 loopback), since {@link SeedResetService#reset()} truncates every domain table and must
     * never run against a shared or production database.
     *
     * @param url the JDBC URL {@code SeedRunner} would seed against
     * @throws IllegalStateException if the URL does not resolve to a localhost variant
     */
    static void assertLocalDatasource(String url) {
        boolean local =
                url != null
                        && (url.contains("localhost")
                                || url.contains("127.0.0.1")
                                || url.contains("[::1]"));
        if (!local) {
            throw new IllegalStateException(
                    "SEED_DATA=true refuses to run against a non-local datasource: " + url);
        }
    }

    private void runSeed() {
        long startedAt = System.currentTimeMillis();
        try {
            if (seedProperties.requireLocalDatasource()) {
                assertLocalDatasource(datasourceUrl);
            }

            log.info("[seed] phase=reset starting");
            resetService.reset();
            log.info("[seed] phase=reset complete");

            log.info("[seed] phase=domain-write starting");
            SeedContent content = new SeedDataLoader().load();
            SeedTimeline timeline = new SeedTimeline(20260825L, java.time.Instant.now());
            WriterChainResult chain = runWriterChain(content, timeline);
            log.info("[seed] phase=domain-write complete");

            log.info("[seed] phase=outbox-emission starting");
            SeedOutboxEmitter.EmissionCounts counts =
                    seedOutboxEmitter.emitFullVolume(
                            content, chain.usersByUsername(), chain.postIdBySeedId());
            log.info("[seed] phase=outbox-emission complete: total={}", counts.total());

            assertEnumCoverage();
            logRowCountSummary();

            log.info(
                    "[seed] full seed run complete in {} ms",
                    System.currentTimeMillis() - startedAt);
            log.info(
                    "[seed] {} outbox_events rows are enqueued as PENDING; the transactional outbox"
                            + " publisher drains them asynchronously on its own schedule"
                            + " (app.outbox.publisher.*), so Elasticsearch, Gorse, and notification"
                            + " state are not yet consistent with this seed - this line marks the"
                            + " synchronous portion done, not the whole system",
                    counts.total());
        } catch (RuntimeException e) {
            log.error("[seed] seed run failed", e);
            throw e;
        }
    }

    /** The id maps later phases need from the writer chain. */
    private record WriterChainResult(
            Map<String, UUID> usersByUsername, Map<String, UUID> postIdBySeedId) {}

    private WriterChainResult runWriterChain(SeedContent content, SeedTimeline timeline) {
        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);
        Map<String, UUID> mediaByCompositeKey = mediaSeedWriter.write(content, usersByUsername);
        Map<String, UUID> postIdBySeedId =
                postSeedWriter.write(content, usersByUsername, mediaByCompositeKey, timeline);
        List<UUID> commentIds =
                commentSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        engagementSeedWriter.write(content, usersByUsername, postIdBySeedId, commentIds, timeline);
        socialGraphSeedWriter.write(content, usersByUsername, timeline);
        storySeedWriter.write(content, usersByUsername, timeline);
        messageSeedWriter.write(
                content, usersByUsername, mediaByCompositeKey, postIdBySeedId, timeline);
        // Must run before NotificationSeedWriter: warning notifications are read back from
        // user_warnings, which this call is what populates.
        moderationSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        // Returns the verification ticket ids, because a badge granted through the request flow
        // anchors to the ticket that produced it.
        Map<String, UUID> verificationTicketIds =
                supportSeedWriter.write(content, usersByUsername, timeline);
        verificationSeedWriter.write(content, usersByUsername, verificationTicketIds, timeline);
        notificationSeedWriter.write(timeline);
        analyticsSeedWriter.write(timeline);
        return new WriterChainResult(usersByUsername, postIdBySeedId);
    }

    /**
     * Queries every one of the 16 mandatory enum-typed columns and asserts every distinct value
     * present in the column's PostgreSQL enum type has at least {@link #MIN_ROWS_PER_ENUM_VALUE}
     * rows.
     *
     * <p>A value that never appears at all is reported the same way as a value that appears but
     * falls short - both are shortfalls the seed content must fix, not a threshold to lower.
     *
     * @throws IllegalStateException naming every column/value pair that fell short of the minimum
     */
    void assertEnumCoverage() {
        List<String> shortfalls = new ArrayList<>();
        for (EnumColumn column : ENUM_COLUMNS) {
            Map<String, Integer> declaredValues = fetchEnumValues(column.enumTypeName());
            Map<String, Integer> actualCounts = fetchColumnCounts(column);
            Set<String> asyncValues =
                    ASYNC_ENUM_VALUES.getOrDefault(
                            column.table() + "." + column.column(), Set.of());
            for (String value : declaredValues.keySet()) {
                if (asyncValues.contains(value)) {
                    continue;
                }
                int count = actualCounts.getOrDefault(value, 0);
                if (count < MIN_ROWS_PER_ENUM_VALUE) {
                    shortfalls.add(
                            column.table()
                                    + "."
                                    + column.column()
                                    + "='"
                                    + value
                                    + "' has "
                                    + count
                                    + " row(s), needs >= "
                                    + MIN_ROWS_PER_ENUM_VALUE);
                }
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new IllegalStateException(
                    "Seed enum coverage assertion failed for "
                            + shortfalls.size()
                            + " value(s): "
                            + String.join("; ", shortfalls));
        }
        log.info("[seed] enum coverage assertion passed for all {} columns", ENUM_COLUMNS.size());
    }

    // Every value a PostgreSQL enum type declares, regardless of whether any row currently uses
    // it - this is what lets a value with zero rows show up as a shortfall instead of being
    // silently absent from the GROUP BY result fetchColumnCounts returns.
    private Map<String, Integer> fetchEnumValues(String enumTypeName) {
        List<String> values =
                jdbc.query(
                        "SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid"
                                + " WHERE t.typname = ?",
                        (rs, rowNum) -> rs.getString(1),
                        enumTypeName);
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (String value : values) {
            result.put(value, 0);
        }
        return result;
    }

    private Map<String, Integer> fetchColumnCounts(EnumColumn column) {
        List<Object[]> rows =
                jdbc.query(
                        "SELECT "
                                + column.column()
                                + "::text AS value, COUNT(*) AS row_count FROM "
                                + column.table()
                                + " WHERE "
                                + column.column()
                                + " IS NOT NULL GROUP BY 1",
                        (rs, rowNum) ->
                                new Object[] {rs.getString("value"), rs.getInt("row_count")});
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Object[] row : rows) {
            counts.put((String) row[0], (Integer) row[1]);
        }
        return counts;
    }

    private void logRowCountSummary() {
        List<String> tables =
                List.of(
                        "users",
                        "media_assets",
                        "posts",
                        "comments",
                        "post_likes",
                        "post_saves",
                        "comment_likes",
                        "follows",
                        "stories",
                        "story_views",
                        "story_likes",
                        "conversations",
                        "messages",
                        "notifications",
                        "reports",
                        "admin_actions",
                        "user_warnings",
                        "user_strikes",
                        "hashtags",
                        "support_tickets",
                        "verification_requests",
                        "platform_stats",
                        "user_events",
                        "outbox_events");
        StringBuilder summary = new StringBuilder("[seed] row count summary:");
        for (String table : tables) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            summary.append(' ').append(table).append('=').append(count);
        }
        log.info(summary.toString());
    }

    private record EnumColumn(String table, String column, String enumTypeName) {}
}
