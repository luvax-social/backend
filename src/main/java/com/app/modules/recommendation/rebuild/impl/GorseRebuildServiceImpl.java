package com.app.modules.recommendation.rebuild.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.app.common.analytics.ClickHouseOperations;
import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.GorsePurgeException;
import com.app.modules.recommendation.client.GorsePurger;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.app.modules.recommendation.client.dto.GorseItemPage;
import com.app.modules.recommendation.client.dto.GorseUser;
import com.app.modules.recommendation.config.GorseRebuildProperties;
import com.app.modules.recommendation.rebuild.GorseRebuildMetrics;
import com.app.modules.recommendation.rebuild.GorseRebuildPhase;
import com.app.modules.recommendation.rebuild.GorseRebuildRun;
import com.app.modules.recommendation.rebuild.GorseRebuildRunRepository;
import com.app.modules.recommendation.rebuild.GorseRebuildService;
import com.app.modules.recommendation.rebuild.GorseRebuildSourceRepository;
import com.app.modules.recommendation.rebuild.GorseRebuildSourceRepository.PostRow;
import com.app.modules.recommendation.rebuild.GorseRebuildStatus;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository.FeedbackTotal;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GorseRebuildServiceImpl implements GorseRebuildService {

    static final String SUSPENSION_REASON = "gorse-rebuild";
    static final String RATE_LIMITER_NAME = "gorseRebuild";

    // A Gorse 5xx or I/O failure is retried three times with these waits; a 4xx never is, since
    // repeating a malformed request cannot succeed.
    private static final long[] RETRY_BACKOFF_MILLIS = {2_000L, 4_000L, 8_000L};
    // The run starts the moment the application is ready, while the ClickHouse schema gate is still
    // checking its migrations in the background, which takes a few seconds even when nothing is
    // left to apply. Preflight waits this long for it rather than failing a run that would have
    // been fine a moment later; a ClickHouse that is really down still fails it.
    private static final int SCHEMA_READY_POLLS = 120;
    private static final long SCHEMA_READY_POLL_MILLIS = 1_000L;
    private static final int VERIFY_PAGE_SIZE = 1_000;
    private static final int VERIFY_FEEDBACK_SAMPLE = 20;
    private static final int EXAMPLE_LIMIT = 20;
    private static final double FEEDBACK_TOLERANCE = 1e-6;
    private static final int MAX_ERROR_LENGTH = 2_000;

    /** Waits between retries; replaced in tests so they need not sleep. */
    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    /** Signals a failure the rebuild recorded a reason for. */
    private static final class RebuildFailure extends RuntimeException {
        RebuildFailure(String message) {
            super(message);
        }
    }

    private final GorseClient gorseClient;
    private final GorsePurger gorsePurger;
    private final GorseRebuildRunRepository runs;
    private final GorseRebuildSourceRepository source;
    private final UserEventAnalyticsRepository userEvents;
    private final HashtagService hashtagService;
    private final ClickHouseOperations clickHouse;
    private final ObjectProvider<AnalyticsIngestionController> ingestionController;
    private final RateLimiter rateLimiter;
    private final GorseRebuildProperties properties;
    private final GorseRebuildMetrics metrics;
    private final ObservationRegistry observationRegistry;
    private final Pause pause;

    @Autowired
    public GorseRebuildServiceImpl(
            GorseClient gorseClient,
            GorsePurger gorsePurger,
            GorseRebuildRunRepository runs,
            GorseRebuildSourceRepository source,
            UserEventAnalyticsRepository userEvents,
            HashtagService hashtagService,
            ClickHouseOperations clickHouse,
            ObjectProvider<AnalyticsIngestionController> ingestionController,
            RateLimiterRegistry rateLimiterRegistry,
            GorseRebuildProperties properties,
            GorseRebuildMetrics metrics,
            ObservationRegistry observationRegistry) {
        this(
                gorseClient,
                gorsePurger,
                runs,
                source,
                userEvents,
                hashtagService,
                clickHouse,
                ingestionController,
                rateLimiterRegistry,
                properties,
                metrics,
                observationRegistry,
                Thread::sleep);
    }

    GorseRebuildServiceImpl(
            GorseClient gorseClient,
            GorsePurger gorsePurger,
            GorseRebuildRunRepository runs,
            GorseRebuildSourceRepository source,
            UserEventAnalyticsRepository userEvents,
            HashtagService hashtagService,
            ClickHouseOperations clickHouse,
            ObjectProvider<AnalyticsIngestionController> ingestionController,
            RateLimiterRegistry rateLimiterRegistry,
            GorseRebuildProperties properties,
            GorseRebuildMetrics metrics,
            ObservationRegistry observationRegistry,
            Pause pause) {
        this.gorseClient = gorseClient;
        this.gorsePurger = gorsePurger;
        this.runs = runs;
        this.source = source;
        this.userEvents = userEvents;
        this.hashtagService = hashtagService;
        this.clickHouse = clickHouse;
        this.ingestionController = ingestionController;
        this.rateLimiter = rateLimiterRegistry.rateLimiter(RATE_LIMITER_NAME);
        this.properties = properties;
        this.metrics = metrics;
        this.observationRegistry = observationRegistry;
        this.pause = pause;
    }

    @Override
    public void execute(GorseRebuildRun run) {
        boolean feedbackSuspended = false;
        try {
            run.setStatus(GorseRebuildStatus.RUNNING);
            run.setLastError(null);
            run.setFinishedAt(null);
            runs.save(run);
            while (run.getPhase() != GorseRebuildPhase.DONE) {
                metrics.entered(run.getPhase());
                // Held from the first phase that changes anything until the end, so no live
                // feedback interleaves with the rebuild; its messages wait in the queue and are
                // applied afterwards, on top of the rebuilt state.
                if (run.getPhase().isAfter(GorseRebuildPhase.PREFLIGHT) && !feedbackSuspended) {
                    suspendFeedbackListener();
                    feedbackSuspended = true;
                }
                switch (run.getPhase()) {
                    case PREFLIGHT -> preflight(run);
                    case PURGE -> purge(run);
                    case USERS -> pushUsers(run);
                    case ITEMS -> pushItems(run);
                    case FEEDBACK -> pushFeedback(run);
                    case VERIFY -> {
                        if (!verify(run)) {
                            return;
                        }
                    }
                    default ->
                            throw new IllegalStateException("unexpected phase " + run.getPhase());
                }
            }
            run.setStatus(GorseRebuildStatus.DONE);
            run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            runs.save(run);
            metrics.entered(GorseRebuildPhase.DONE);
            log.info(
                    "[gorse-rebuild] token={} phase=DONE users={} items={} feedback={}"
                            + " feedbackSkipped={}",
                    run.getToken(),
                    run.getUsersSent(),
                    run.getItemsSent(),
                    run.getFeedbackSent(),
                    run.getFeedbackSkipped());
        } catch (RuntimeException e) {
            fail(run, e);
        } finally {
            if (feedbackSuspended) {
                resumeFeedbackListener();
            }
        }
    }

    // Checks everything the run depends on before it changes anything, so a run that cannot finish
    // ends here with the stores untouched.
    private void preflight(GorseRebuildRun run) {
        awaitSchemaReady();
        try {
            rateLimiter.executeSupplier(() -> gorseClient.listItems(null, 1));
        } catch (RestClientException e) {
            throw new RebuildFailure("Gorse did not answer GET /api/items: " + e.getMessage());
        }
        List<String> obstacles;
        try {
            obstacles = gorsePurger.findPurgeObstacles();
        } catch (GorsePurgeException e) {
            throw new RebuildFailure(e.getMessage());
        }
        if (!obstacles.isEmpty()) {
            throw new RebuildFailure(
                    "Gorse's store cannot be purged: "
                            + String.join("; ", obstacles)
                            + ". Grant the missing privileges, then restart with the same token.");
        }
        advance(run, GorseRebuildPhase.PURGE);
    }

    private void awaitSchemaReady() {
        for (int poll = 0; !clickHouse.isReady(); poll++) {
            if (poll == SCHEMA_READY_POLLS) {
                throw new RebuildFailure(
                        "the ClickHouse analytics schema is not ready, so the feedback cannot be"
                                + " read");
            }
            try {
                pause.sleep(SCHEMA_READY_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RebuildFailure("interrupted while waiting for the analytics schema");
            }
        }
    }

    private void purge(GorseRebuildRun run) {
        try {
            gorsePurger.purge();
        } catch (GorsePurgeException e) {
            throw new RebuildFailure(e.getMessage());
        }
        advance(run, GorseRebuildPhase.USERS);
    }

    private void pushUsers(GorseRebuildRun run) {
        int batchSize = properties.userBatchSize();
        while (true) {
            List<UUID> ids = source.findUserIdsAfter(run.getCheckpointId(), batchSize);
            if (ids.isEmpty()) {
                break;
            }
            List<GorseUser> users =
                    ids.stream().map(id -> new GorseUser(id.toString(), List.of(), "")).toList();
            observe(run, () -> gorse(() -> voidCall(() -> gorseClient.upsertUsers(users))));
            run.setUsersSent(run.getUsersSent() + ids.size());
            run.setCheckpointId(ids.get(ids.size() - 1));
            runs.save(run);
            metrics.sent(GorseRebuildMetrics.Kind.USERS, ids.size());
            logBatch(run, run.getUsersSent());
            if (ids.size() < batchSize) {
                break;
            }
        }
        advance(run, GorseRebuildPhase.ITEMS);
    }

    private void pushItems(GorseRebuildRun run) {
        int batchSize = properties.itemBatchSize();
        while (true) {
            List<PostRow> posts = source.findPostsAfter(run.getCheckpointId(), batchSize);
            if (posts.isEmpty()) {
                break;
            }
            Map<UUID, List<String>> labels =
                    hashtagService.getHashtagNamesForPosts(
                            posts.stream().map(PostRow::id).toList());
            List<GorseItem> items =
                    posts.stream()
                            .map(
                                    post ->
                                            new GorseItem(
                                                    post.id().toString(),
                                                    post.hidden(),
                                                    List.of(),
                                                    labels.getOrDefault(post.id(), List.of()),
                                                    post.createdAt(),
                                                    null))
                            .toList();
            observe(run, () -> gorse(() -> voidCall(() -> gorseClient.upsertItems(items))));
            run.setItemsSent(run.getItemsSent() + posts.size());
            run.setCheckpointId(posts.get(posts.size() - 1).id());
            runs.save(run);
            metrics.sent(GorseRebuildMetrics.Kind.ITEMS, posts.size());
            logBatch(run, run.getItemsSent());
            if (posts.size() < batchSize) {
                break;
            }
        }
        advance(run, GorseRebuildPhase.FEEDBACK);
    }

    private void pushFeedback(GorseRebuildRun run) {
        int userBatchSize = properties.userBatchSize();
        while (true) {
            List<UUID> userIds = source.findUserIdsAfter(run.getCheckpointId(), userBatchSize);
            if (userIds.isEmpty()) {
                break;
            }
            List<FeedbackTotal> totals = userEvents.findFeedbackTotals(userIds);
            Set<UUID> knownPosts =
                    source.findExistingPostIds(totals.stream().map(FeedbackTotal::itemId).toList());
            List<GorseFeedback> feedback = new ArrayList<>();
            long skipped = 0;
            for (FeedbackTotal total : totals) {
                if (!knownPosts.contains(total.itemId())) {
                    skipped++;
                    continue;
                }
                feedback.add(
                        new GorseFeedback(
                                total.feedbackType(),
                                total.userId().toString(),
                                total.itemId().toString(),
                                total.latest(),
                                total.value()));
            }
            int chunkSize = properties.feedbackBatchSize();
            for (int from = 0; from < feedback.size(); from += chunkSize) {
                List<GorseFeedback> chunk =
                        feedback.subList(from, Math.min(from + chunkSize, feedback.size()));
                observe(run, () -> gorse(() -> voidCall(() -> gorseClient.upsertFeedback(chunk))));
            }
            run.setFeedbackSent(run.getFeedbackSent() + feedback.size());
            run.setFeedbackSkipped(run.getFeedbackSkipped() + skipped);
            run.setCheckpointId(userIds.get(userIds.size() - 1));
            runs.save(run);
            metrics.sent(GorseRebuildMetrics.Kind.FEEDBACK, feedback.size());
            metrics.skippedUnknownPost(skipped);
            logBatch(run, run.getFeedbackSent());
            if (userIds.size() < userBatchSize) {
                break;
            }
        }
        advance(run, GorseRebuildPhase.VERIFY);
    }

    // Compares what Gorse holds with PostgreSQL in both directions, then spot-checks feedback
    // values against the ClickHouse sums. Returns false after recording FAILED_VERIFICATION.
    private boolean verify(GorseRebuildRun run) {
        Map<UUID, Boolean> expectedHidden = new HashMap<>();
        for (PostRow post : source.findAllPosts()) {
            expectedHidden.put(post.id(), post.hidden());
        }
        Map<String, Boolean> heldHidden = new HashMap<>();
        String cursor = "";
        do {
            String position = cursor;
            GorseItemPage page = gorse(() -> gorseClient.listItems(position, VERIFY_PAGE_SIZE));
            for (GorseItem item : page.items()) {
                heldHidden.put(item.itemId(), item.hidden());
            }
            cursor = page.cursor() == null ? "" : page.cursor();
        } while (!cursor.isEmpty());

        List<String> missing = new ArrayList<>();
        List<String> stray = new ArrayList<>();
        List<String> hiddenMismatch = new ArrayList<>();
        for (Map.Entry<UUID, Boolean> expected : expectedHidden.entrySet()) {
            Boolean held = heldHidden.get(expected.getKey().toString());
            if (held == null) {
                missing.add(expected.getKey().toString());
            } else if (!held.equals(expected.getValue())) {
                hiddenMismatch.add(
                        expected.getKey() + " (expected hidden=" + expected.getValue() + ")");
            }
        }
        Set<String> expectedIds = new HashSet<>();
        expectedHidden.keySet().forEach(id -> expectedIds.add(id.toString()));
        for (String held : heldHidden.keySet()) {
            if (!expectedIds.contains(held)) {
                stray.add(held);
            }
        }
        List<String> feedbackDiffs = compareFeedbackSample();

        if (missing.isEmpty()
                && stray.isEmpty()
                && hiddenMismatch.isEmpty()
                && feedbackDiffs.isEmpty()) {
            advance(run, GorseRebuildPhase.DONE);
            return true;
        }
        String summary =
                "missing="
                        + missing.size()
                        + " stray="
                        + stray.size()
                        + " hiddenMismatch="
                        + hiddenMismatch.size()
                        + " feedbackMismatch="
                        + feedbackDiffs.size();
        log.error(
                "[gorse-rebuild] token={} phase=VERIFY FAILED_VERIFICATION {} missing={} stray={}"
                        + " hiddenMismatch={} feedbackMismatch={}",
                run.getToken(),
                summary,
                first(missing),
                first(stray),
                first(hiddenMismatch),
                first(feedbackDiffs));
        metrics.verificationFailed();
        metrics.failed();
        run.setStatus(GorseRebuildStatus.FAILED_VERIFICATION);
        run.setLastError(truncate(summary));
        run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        runs.save(run);
        return false;
    }

    // Reads back a sample of feedback tuples from Gorse and compares them with the ClickHouse sums.
    private List<String> compareFeedbackSample() {
        List<FeedbackTotal> sample = new ArrayList<>();
        UUID cursor = null;
        while (sample.size() < VERIFY_FEEDBACK_SAMPLE) {
            List<UUID> userIds = source.findUserIdsAfter(cursor, properties.userBatchSize());
            if (userIds.isEmpty()) {
                break;
            }
            List<FeedbackTotal> totals = userEvents.findFeedbackTotals(userIds);
            Set<UUID> knownPosts =
                    source.findExistingPostIds(totals.stream().map(FeedbackTotal::itemId).toList());
            for (FeedbackTotal total : totals) {
                if (sample.size() < VERIFY_FEEDBACK_SAMPLE && knownPosts.contains(total.itemId())) {
                    sample.add(total);
                }
            }
            cursor = userIds.get(userIds.size() - 1);
            if (userIds.size() < properties.userBatchSize()) {
                break;
            }
        }
        List<String> diffs = new ArrayList<>();
        for (FeedbackTotal total : sample) {
            String key = total.feedbackType() + "/" + total.userId() + "/" + total.itemId();
            var held =
                    gorse(
                            () ->
                                    gorseClient.getFeedback(
                                            total.feedbackType(),
                                            total.userId().toString(),
                                            total.itemId().toString()));
            if (held.isEmpty()) {
                diffs.add(key + " absent, expected " + total.value());
            } else if (Math.abs(held.get().value() - total.value()) > FEEDBACK_TOLERANCE) {
                diffs.add(key + " holds " + held.get().value() + ", expected " + total.value());
            }
        }
        return diffs;
    }

    private void advance(GorseRebuildRun run, GorseRebuildPhase next) {
        run.enter(next);
        runs.save(run);
    }

    private void fail(GorseRebuildRun run, RuntimeException failure) {
        String reason =
                failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
        metrics.failed();
        log.error(
                "[gorse-rebuild] token={} phase={} FAILED: {}",
                run.getToken(),
                run.getPhase(),
                reason);
        if (!(failure instanceof RebuildFailure)) {
            log.error("[gorse-rebuild] token={} unexpected failure", run.getToken(), failure);
        }
        try {
            run.setStatus(GorseRebuildStatus.FAILED);
            run.setLastError(truncate(reason));
            runs.save(run);
        } catch (RuntimeException saveFailure) {
            log.error(
                    "[gorse-rebuild] token={} the failure could not be recorded: {}",
                    run.getToken(),
                    saveFailure.getMessage());
        }
    }

    // One Gorse call: rate limited, retried on a server or I/O failure, failed at once on a 4xx.
    private <T> T gorse(Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return RateLimiter.decorateSupplier(rateLimiter, call).get();
            } catch (HttpClientErrorException e) {
                throw new RebuildFailure(
                        "Gorse rejected a request with "
                                + e.getStatusCode()
                                + ": "
                                + e.getMessage());
            } catch (RestClientException e) {
                if (attempt >= RETRY_BACKOFF_MILLIS.length) {
                    throw new RebuildFailure(
                            "Gorse kept failing after "
                                    + RETRY_BACKOFF_MILLIS.length
                                    + " retries: "
                                    + e.getMessage());
                }
                log.warn(
                        "[gorse-rebuild] Gorse call failed, retrying in {} ms: {}",
                        RETRY_BACKOFF_MILLIS[attempt],
                        e.getMessage());
                try {
                    pause.sleep(RETRY_BACKOFF_MILLIS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RebuildFailure("interrupted while waiting to retry Gorse");
                }
            }
        }
    }

    private static Object voidCall(Runnable call) {
        call.run();
        return null;
    }

    private void observe(GorseRebuildRun run, Runnable batch) {
        Observation.createNotStarted("gorse.rebuild.batch", observationRegistry)
                .lowCardinalityKeyValue("phase", run.getPhase().name())
                .observe(batch);
    }

    private void logBatch(GorseRebuildRun run, long sent) {
        log.info(
                "[gorse-rebuild] token={} phase={} sent={} checkpoint={}",
                run.getToken(),
                run.getPhase(),
                sent,
                run.getCheckpointId());
    }

    private void suspendFeedbackListener() {
        AnalyticsIngestionController controller = ingestionController.getIfAvailable();
        if (controller != null) {
            controller.suspend(SUSPENSION_REASON);
        }
    }

    private void resumeFeedbackListener() {
        AnalyticsIngestionController controller = ingestionController.getIfAvailable();
        if (controller != null) {
            controller.resume(SUSPENSION_REASON);
        }
    }

    private static List<String> first(List<String> values) {
        return values.subList(0, Math.min(EXAMPLE_LIMIT, values.size()));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
