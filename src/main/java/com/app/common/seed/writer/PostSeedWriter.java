package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.HashtagSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code posts}, {@code post_media}, {@code hashtags}, {@code post_hashtags} and {@code
 * hashtag_trending} from {@code posts.json} and {@code hashtags.json}.
 *
 * <p><b>{@code view_count}</b>: the one denormalized counter with no trigger (see {@code
 * post/DATA_RULES.md} Section 2 and {@code GLOBAL_RULES.md} Section 2's counter-policy exception),
 * so this writer is the only place in the whole seed pipeline that must set it explicitly. Every
 * other counter on {@code posts} (`like_count`, `comment_count`, `save_count`) is left untouched
 * and stays at its {@code DEFAULT 0} until {@link CommentSeedWriter} / {@link EngagementSeedWriter}
 * run and the triggers fire.
 *
 * <p><b>{@code post_hashtags} linkage contract</b>: only a {@code published} post is given {@code
 * post_hashtags} rows, matching the production invariant that a draft or archived post has never
 * gone through {@code PostServiceImpl.upsertCaptionHashtags}, and that a moderation removal
 * detaches every association it held (`post/DATA_RULES.md` Section 3B). A hashtag's own row is
 * written regardless of whether any published post ends up referencing it - the registry in {@code
 * hashtags.json} is loaded in full, matching {@code hashtag/DATA_RULES.md}'s statement that a
 * hashtag exists independently of any one post.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class PostSeedWriter {

    // Deliberately separate from SeedTimeline's own Random stream (used only for timestamps): this
    // stream drives view_count magnitudes, a concern SeedTimeline has no method for.
    private static final long VIEW_COUNT_RANDOM_SEED = 7_402_913L;

    private static final Map<String, int[]> VIEW_COUNT_RANGE_BY_BAND =
            Map.of(
                    "low", new int[] {20, 300},
                    "medium", new int[] {300, 3_000},
                    "high", new int[] {3_000, 15_000},
                    "viral", new int[] {15_000, 120_000});

    private static final Duration TRENDING_WINDOW = Duration.ofDays(7);
    private static final String PUBLISHED_STATUS = "published";
    private static final String REMOVED_STATUS = "removed";

    private static final String INSERT_POST_SQL =
            "INSERT INTO posts (id, user_id, caption, post_type, status,"
                    + " status_before_moderation, view_count, created_at) VALUES (?, ?, ?,"
                    + " ?::post_type, ?::post_status, ?::post_status, ?, ?)";
    private static final String INSERT_POST_MEDIA_SQL =
            "INSERT INTO post_media (id, post_id, media_asset_id, position, created_at) VALUES"
                    + " (?, ?, ?, ?, ?)";
    private static final String INSERT_HASHTAG_SQL =
            "INSERT INTO hashtags (id, name, status, created_at) VALUES (?, ?, ?::hashtag_status,"
                    + " ?)";
    private static final String INSERT_POST_HASHTAG_SQL =
            "INSERT INTO post_hashtags (post_id, hashtag_id, created_at) VALUES (?, ?, ?)";
    private static final String INSERT_HASHTAG_TRENDING_SQL =
            "INSERT INTO hashtag_trending (hashtag_id, period_start, period_end, post_count,"
                    + " rank, created_at) VALUES (?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    /**
     * Inserts every post from {@code posts.json} (with its media and hashtag associations) and the
     * full {@code hashtags.json} registry, and returns the seed-id-to-generated-id mapping later
     * writers ({@link CommentSeedWriter}, {@link EngagementSeedWriter}, {@link
     * com.app.common.seed.outbox.SeedOutboxEmitter}) resolve post references through.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @param mediaByCompositeKey composite-key map produced by {@link MediaSeedWriter#write}
     * @return a map from {@link PostSeed#id()} to the generated {@code posts.id}, one entry per
     *     post in {@code content.posts()}
     */
    public Map<String, UUID> write(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> mediaByCompositeKey,
            SeedTimeline timeline) {
        List<PostSeed> posts = content.posts();
        Random viewCountRandom = new Random(VIEW_COUNT_RANDOM_SEED);

        Map<String, UUID> postIdBySeedId = new HashMap<>();
        Map<String, Instant> createdAtBySeedId = new HashMap<>();
        // Only published posts feed earliest-use timestamps and hashtag_trending's window - a
        // draft/archived/removed post never actually associated the hashtag in production.
        Map<String, Instant> earliestUseByHashtagName = new HashMap<>();
        Instant globalMinCreatedAt = Instant.MAX;
        Instant maxPublishedCreatedAt = Instant.MIN;

        List<Object[]> postRows = new ArrayList<>();
        List<Object[]> postMediaRows = new ArrayList<>();

        for (PostSeed post : posts) {
            UUID authorId = usersByUsername.get(post.authorUsername());
            if (authorId == null) {
                throw new IllegalStateException(
                        "PostSeedWriter: no persisted user found for author_username '"
                                + post.authorUsername()
                                + "' (post '"
                                + post.id()
                                + "') - UserSeedWriter must run before PostSeedWriter");
            }

            UUID postId = UUID.randomUUID();
            Instant createdAt = timeline.postCreatedAt(post);
            postIdBySeedId.put(post.id(), postId);
            createdAtBySeedId.put(post.id(), createdAt);
            if (createdAt.isBefore(globalMinCreatedAt)) {
                globalMinCreatedAt = createdAt;
            }

            int viewCount = randomViewCount(post.engagementBand(), viewCountRandom);
            String statusBeforeModeration =
                    REMOVED_STATUS.equals(post.status()) ? PUBLISHED_STATUS : null;

            postRows.add(
                    new Object[] {
                        postId,
                        authorId,
                        post.caption(),
                        post.postType(),
                        post.status(),
                        statusBeforeModeration,
                        viewCount,
                        Timestamp.from(createdAt)
                    });

            List<String> mediaRefs = post.mediaRefs();
            for (int position = 0; position < mediaRefs.size(); position++) {
                String mediaRef = mediaRefs.get(position);
                UUID mediaAssetId =
                        mediaByCompositeKey.get(MediaSeedWriter.compositeKey(mediaRef, authorId));
                if (mediaAssetId == null) {
                    throw new IllegalStateException(
                            "PostSeedWriter: no persisted media_assets row found for media_ref '"
                                    + mediaRef
                                    + "', owner "
                                    + authorId
                                    + " (post '"
                                    + post.id()
                                    + "') - MediaSeedWriter must run before PostSeedWriter");
                }
                postMediaRows.add(
                        new Object[] {
                            UUID.randomUUID(),
                            postId,
                            mediaAssetId,
                            (short) position,
                            Timestamp.from(createdAt)
                        });
            }

            if (PUBLISHED_STATUS.equals(post.status())) {
                if (createdAt.isAfter(maxPublishedCreatedAt)) {
                    maxPublishedCreatedAt = createdAt;
                }
                for (String hashtagName : post.hashtagNames()) {
                    earliestUseByHashtagName.merge(
                            hashtagName, createdAt, (a, b) -> a.isBefore(b) ? a : b);
                }
            }
        }

        jdbc.batchUpdate(INSERT_POST_SQL, postRows, postRows.size(), this::bindPostRow);
        jdbc.batchUpdate(
                INSERT_POST_MEDIA_SQL, postMediaRows, postMediaRows.size(), this::bindPostMediaRow);
        log.info(
                "[seed] posts: {} rows written, post_media: {} rows written",
                postRows.size(),
                postMediaRows.size());

        Map<String, UUID> hashtagIdByName =
                writeHashtags(content.hashtags(), earliestUseByHashtagName, globalMinCreatedAt);
        Map<String, Integer> linkCountByHashtagName =
                writePostHashtags(posts, postIdBySeedId, createdAtBySeedId, hashtagIdByName);
        writeHashtagTrending(
                content.hashtags(), hashtagIdByName, linkCountByHashtagName, maxPublishedCreatedAt);

        return postIdBySeedId;
    }

    // hashtags.json's full 150-entry catalog is loaded regardless of post usage: hashtag/DATA_
    // RULES.md's "created on first use" describes the production write path, not a constraint on
    // what a seed registry may contain. Every entry's created_at is the earliest published post
    // that referenced it, or globalMinCreatedAt for the handful never referenced by any post.
    private Map<String, UUID> writeHashtags(
            List<HashtagSeed> hashtags,
            Map<String, Instant> earliestUseByHashtagName,
            Instant globalMinCreatedAt) {
        Map<String, UUID> hashtagIdByName = new HashMap<>();
        List<Object[]> hashtagRows = new ArrayList<>();
        for (HashtagSeed hashtag : hashtags) {
            UUID hashtagId = UUID.randomUUID();
            hashtagIdByName.put(hashtag.name(), hashtagId);
            Instant createdAt =
                    earliestUseByHashtagName.getOrDefault(hashtag.name(), globalMinCreatedAt);
            hashtagRows.add(
                    new Object[] {
                        hashtagId, hashtag.name(), hashtag.status(), Timestamp.from(createdAt)
                    });
        }
        jdbc.batchUpdate(INSERT_HASHTAG_SQL, hashtagRows, hashtagRows.size(), this::bindHashtagRow);
        log.info("[seed] hashtags: {} rows written", hashtagRows.size());
        return hashtagIdByName;
    }

    private Map<String, Integer> writePostHashtags(
            List<PostSeed> posts,
            Map<String, UUID> postIdBySeedId,
            Map<String, Instant> createdAtBySeedId,
            Map<String, UUID> hashtagIdByName) {
        Map<String, Integer> linkCountByHashtagName = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (PostSeed post : posts) {
            if (!PUBLISHED_STATUS.equals(post.status())) {
                continue;
            }
            UUID postId = postIdBySeedId.get(post.id());
            Timestamp createdAt = Timestamp.from(createdAtBySeedId.get(post.id()));
            for (String hashtagName : post.hashtagNames()) {
                UUID hashtagId = hashtagIdByName.get(hashtagName);
                if (hashtagId == null) {
                    throw new IllegalStateException(
                            "PostSeedWriter: post '"
                                    + post.id()
                                    + "' references hashtag_names entry '"
                                    + hashtagName
                                    + "' which does not exist in hashtags.json");
                }
                rows.add(new Object[] {postId, hashtagId, createdAt});
                linkCountByHashtagName.merge(hashtagName, 1, Integer::sum);
            }
        }
        jdbc.batchUpdate(INSERT_POST_HASHTAG_SQL, rows, rows.size(), this::bindPostHashtagRow);
        log.info("[seed] post_hashtags: {} rows written", rows.size());
        return linkCountByHashtagName;
    }

    private void writeHashtagTrending(
            List<HashtagSeed> hashtags,
            Map<String, UUID> hashtagIdByName,
            Map<String, Integer> linkCountByHashtagName,
            Instant periodEnd) {
        List<HashtagSeed> trending = hashtags.stream().filter(HashtagSeed::isTrending).toList();
        List<HashtagSeed> ranked =
                trending.stream()
                        .sorted(
                                (a, b) -> {
                                    int countA = linkCountByHashtagName.getOrDefault(a.name(), 0);
                                    int countB = linkCountByHashtagName.getOrDefault(b.name(), 0);
                                    if (countA != countB) {
                                        return Integer.compare(countB, countA);
                                    }
                                    return a.name().compareTo(b.name());
                                })
                        .toList();

        Instant periodStart = periodEnd.minus(TRENDING_WINDOW);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            HashtagSeed hashtag = ranked.get(i);
            UUID hashtagId = hashtagIdByName.get(hashtag.name());
            int postCount = linkCountByHashtagName.getOrDefault(hashtag.name(), 0);
            rows.add(
                    new Object[] {
                        hashtagId,
                        Timestamp.from(periodStart),
                        Timestamp.from(periodEnd),
                        postCount,
                        i + 1,
                        Timestamp.from(periodEnd)
                    });
        }
        jdbc.batchUpdate(
                INSERT_HASHTAG_TRENDING_SQL, rows, rows.size(), this::bindHashtagTrendingRow);
        log.info("[seed] hashtag_trending: {} rows written", rows.size());
    }

    private int randomViewCount(String engagementBand, Random random) {
        int[] range = VIEW_COUNT_RANGE_BY_BAND.get(engagementBand);
        if (range == null) {
            // Defensive fallback: posts.json's engagement_band vocabulary is closed to
            // low/medium/high/viral, but an unrecognized value must still yield a plausible
            // non-zero count rather than crash the whole seed run.
            range = VIEW_COUNT_RANGE_BY_BAND.get("low");
        }
        return range[0] + random.nextInt(range[1] - range[0]);
    }

    private void bindPostRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setString(3, (String) row[2]);
        ps.setString(4, (String) row[3]);
        ps.setString(5, (String) row[4]);
        ps.setString(6, (String) row[5]);
        ps.setInt(7, (Integer) row[6]);
        ps.setTimestamp(8, (Timestamp) row[7]);
    }

    private void bindPostMediaRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
        ps.setShort(4, (Short) row[3]);
        ps.setTimestamp(5, (Timestamp) row[4]);
    }

    private void bindHashtagRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setString(2, (String) row[1]);
        ps.setString(3, (String) row[2]);
        ps.setTimestamp(4, (Timestamp) row[3]);
    }

    private void bindPostHashtagRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setTimestamp(3, (Timestamp) row[2]);
    }

    private void bindHashtagTrendingRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setTimestamp(2, (Timestamp) row[1]);
        ps.setTimestamp(3, (Timestamp) row[2]);
        ps.setInt(4, (Integer) row[3]);
        ps.setInt(5, (Integer) row[4]);
        ps.setTimestamp(6, (Timestamp) row[5]);
    }
}
