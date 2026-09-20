package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code post_likes} (~15,000), {@code post_saves} (~2,000) and {@code comment_likes}
 * (~4,000) across the posts and comments {@link PostSeedWriter} and {@link CommentSeedWriter} have
 * already written.
 *
 * <p>Post selection is weighted by {@link PostSeed#engagementBand()} - a {@code viral} post draws
 * far more of the like/save budget than a {@code low} post - matching the same
 * band-drives-magnitude principle {@link PostSeedWriter} applies to {@code view_count}. Comment
 * selection has no band to weight against, so it is uniform over every non-soft-deleted comment.
 *
 * <p>Never writes {@code posts.like_count}, {@code posts.save_count} or {@code comments.like_count}
 * - all three are trigger-maintained (see {@code post/DATA_RULES.md} and {@code
 * comment/DATA_RULES.md} Section 2); this writer's own {@code INSERT} statements touch only the
 * junction tables the triggers read from.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class EngagementSeedWriter {

    // Deliberately separate from SeedTimeline's own Random stream (used only for timestamps): this
    // stream drives which (user, post)/(user, comment) pairs are chosen.
    private static final long ENGAGEMENT_RANDOM_SEED = 3_180_442L;

    private static final int POST_LIKE_TARGET = 20_000;
    // Accounts that never post. They are weighted up as likers because consuming is the only
    // thing they do, and a uniform draw would make them exactly as active as an account that
    // also writes - which is not what the persona describes.
    private static final Set<String> CONSUMER_PERSONAS =
            Set.of("p09_lurker_commenter", "p11_silent_consumer");
    private static final int CONSUMER_LIKE_WEIGHT = 3;
    private static final int POST_SAVE_TARGET = 2_000;
    private static final int COMMENT_LIKE_TARGET = 4_000;
    private static final int MAX_ATTEMPT_MULTIPLIER = 30;

    private static final Map<String, Integer> POST_WEIGHT_BY_BAND =
            Map.of(
                    "low", 1,
                    "medium", 3,
                    "high", 8,
                    "viral", 20);

    private static final String INSERT_POST_LIKE_SQL =
            "INSERT INTO post_likes (user_id, post_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO"
                    + " NOTHING";
    private static final String INSERT_POST_SAVE_SQL =
            "INSERT INTO post_saves (user_id, post_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO"
                    + " NOTHING";
    private static final String INSERT_COMMENT_LIKE_SQL =
            "INSERT INTO comment_likes (user_id, comment_id, created_at) VALUES (?, ?, ?) ON"
                    + " CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    /**
     * Inserts the seeded {@code post_likes}, {@code post_saves} and {@code comment_likes} rows.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @param postIdBySeedId seed-id-to-generated-id map produced by {@link PostSeedWriter#write}
     * @param commentIds every generated comment id produced by {@link CommentSeedWriter#write}
     */
    public void write(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            List<UUID> commentIds,
            SeedTimeline timeline) {
        List<UUID> userIds = buildWeightedUserPool(content, usersByUsername);
        Random random = new Random(ENGAGEMENT_RANDOM_SEED);

        WeightedPostIndex weightedPosts = buildWeightedPosts(content.posts(), postIdBySeedId);
        Map<UUID, Instant> postCreatedAtById = fetchCreatedAtById("posts");
        Map<UUID, Instant> commentCreatedAtById = fetchNonDeletedCommentCreatedAt(commentIds);

        int postLikeCount =
                writeReactions(
                        INSERT_POST_LIKE_SQL,
                        POST_LIKE_TARGET,
                        () -> weightedPosts.pick(random),
                        userIds,
                        postCreatedAtById,
                        random,
                        timeline);
        int postSaveCount =
                writeReactions(
                        INSERT_POST_SAVE_SQL,
                        POST_SAVE_TARGET,
                        () -> weightedPosts.pick(random),
                        userIds,
                        postCreatedAtById,
                        random,
                        timeline);
        List<UUID> nonDeletedCommentIds = new ArrayList<>(commentCreatedAtById.keySet());
        int commentLikeCount =
                writeReactions(
                        INSERT_COMMENT_LIKE_SQL,
                        COMMENT_LIKE_TARGET,
                        () -> nonDeletedCommentIds.get(random.nextInt(nonDeletedCommentIds.size())),
                        userIds,
                        commentCreatedAtById,
                        random,
                        timeline);

        log.info(
                "[seed] post_likes: {} rows written, post_saves: {} rows written, comment_likes:"
                        + " {} rows written",
                postLikeCount,
                postSaveCount,
                commentLikeCount);
    }

    // Shared engine for all three junction tables: repeatedly draws a (user, target) pair via the
    // given target-picker, dedupes locally (the compound PK would reject a repeat anyway, but a
    // local Set avoids burning attempts on a guaranteed-duplicate INSERT), and stops once the
    // target row count is reached or the attempt budget is exhausted.
    // Repeats a consumer account's id in the draw pool rather than branching inside the draw
    // loop, so the existing uniform pick stays uniform over a pool that is itself weighted. The
    // local dedupe in writeReactions means a repeated id costs at most one wasted attempt.
    private List<UUID> buildWeightedUserPool(
            SeedContent content, Map<String, UUID> usersByUsername) {
        List<UUID> pool = new ArrayList<>();
        for (UserSeed user : content.users()) {
            UUID id = usersByUsername.get(user.username());
            if (id == null) {
                continue;
            }
            int weight = CONSUMER_PERSONAS.contains(user.personaId()) ? CONSUMER_LIKE_WEIGHT : 1;
            for (int i = 0; i < weight; i++) {
                pool.add(id);
            }
        }
        return pool;
    }

    private int writeReactions(
            String insertSql,
            int targetCount,
            java.util.function.Supplier<UUID> targetIdPicker,
            List<UUID> userIds,
            Map<UUID, Instant> createdAtByTargetId,
            Random random,
            SeedTimeline timeline) {
        List<Object[]> rows = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();
        int maxAttempts = targetCount * MAX_ATTEMPT_MULTIPLIER;
        int attempts = 0;
        while (rows.size() < targetCount && attempts < maxAttempts) {
            attempts++;
            UUID targetId = targetIdPicker.get();
            UUID userId = userIds.get(random.nextInt(userIds.size()));
            String pairKey = userId + "::" + targetId;
            if (!seenPairs.add(pairKey)) {
                continue;
            }
            Instant targetCreatedAt = createdAtByTargetId.get(targetId);
            if (targetCreatedAt == null) {
                continue;
            }
            Instant createdAt = timeline.likeOrSaveCreatedAt(targetCreatedAt);
            rows.add(new Object[] {userId, targetId, Timestamp.from(createdAt)});
        }
        jdbc.batchUpdate(insertSql, rows, rows.size(), this::bindReactionRow);
        return rows.size();
    }

    private WeightedPostIndex buildWeightedPosts(
            List<PostSeed> posts, Map<String, UUID> postIdBySeedId) {
        List<UUID> postIds = new ArrayList<>();
        List<Integer> cumulativeWeights = new ArrayList<>();
        int runningTotal = 0;
        for (PostSeed post : posts) {
            UUID postId = postIdBySeedId.get(post.id());
            if (postId == null) {
                continue;
            }
            int weight = POST_WEIGHT_BY_BAND.getOrDefault(post.engagementBand(), 1);
            runningTotal += weight;
            postIds.add(postId);
            cumulativeWeights.add(runningTotal);
        }
        return new WeightedPostIndex(
                postIds.toArray(new UUID[0]),
                cumulativeWeights.stream().mapToInt(Integer::intValue).toArray(),
                runningTotal);
    }

    private Map<UUID, Instant> fetchCreatedAtById(String table) {
        Map<UUID, Instant> createdAtById = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM " + table,
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    createdAtById.put(id, createdAt);
                });
        return createdAtById;
    }

    // Soft-deleted comments are excluded: a like arriving after a comment was already deleted has
    // no realistic counterpart in the production write path, which requires the comment to be
    // visible (deleted_at IS NULL) to be liked at all.
    private Map<UUID, Instant> fetchNonDeletedCommentCreatedAt(List<UUID> commentIds) {
        Map<UUID, Instant> createdAtById = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM comments WHERE deleted_at IS NULL"
                        + " AND admin_removed_at IS NULL",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    createdAtById.put(id, createdAt);
                });
        // commentIds is authoritative for "every id CommentSeedWriter generated"; intersecting
        // guards against a caller passing a stale list from a different seed run.
        createdAtById.keySet().retainAll(new HashSet<>(commentIds));
        return createdAtById;
    }

    private void bindReactionRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setTimestamp(3, (Timestamp) row[2]);
    }

    // Precomputed cumulative-weight lookup for O(log n) weighted post selection, reused across the
    // post_likes and post_saves passes so the weight distribution is built exactly once per run.
    private record WeightedPostIndex(UUID[] postIds, int[] cumulativeWeights, int totalWeight) {
        UUID pick(Random random) {
            int target = random.nextInt(totalWeight);
            int index = java.util.Arrays.binarySearch(cumulativeWeights, target);
            if (index < 0) {
                index = -index - 1;
            } else {
                // An exact hit on a cumulative boundary belongs to the next bucket, since
                // cumulativeWeights[i] is the exclusive upper bound of postIds[i]'s range.
                index++;
            }
            return postIds[Math.min(index, postIds.length - 1)];
        }
    }
}
