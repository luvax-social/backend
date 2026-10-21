package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.CommentChainSeed;
import com.app.common.seed.model.CommentChainTurn;
import com.app.common.seed.model.CommentPoolEntry;
import com.app.common.seed.model.HiddenCommentSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.outbox.SeedOutboxBatchWriter.UserEventImport;
import com.app.common.seed.time.SeedTimeline;
import com.app.modules.recommendation.enums.UserEventType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code comments} from {@code comment_pools.json}'s 900 pooled comment texts and 82 scripted
 * reply chains ({@code conversation_seeds}).
 *
 * <p><b>Adjacency-list convention</b>: matches the pattern the deleted {@code
 * DevDataSeedServiceImpl.insertComment} demonstrated - a root comment self-references {@code
 * root_id} (its own generated id, never {@code NULL}) and every reply inherits the root above it.
 * This differs from {@code CommentServiceImpl.create}'s production behaviour, which leaves a
 * top-level comment's {@code root_id NULL}, but every subtree query in {@code CommentRepository}
 * reads {@code coalesce(root_id, id)}, so the two conventions are interchangeable to every reader
 * and the self-referencing form is the one this writer was directed to use.
 *
 * <p><b>{@code commentCreatedAt} resolution</b>: {@link SeedTimeline#commentCreatedAt(Instant)}
 * takes a single {@code Instant}, not a {@code CommentSeed}-typed parameter - it is generic over
 * "the instant this comment must not precede", so it is reused unchanged for both purposes this
 * writer needs: a root comment is timed off its post's {@code created_at}, and a reply is timed off
 * its parent comment's {@code created_at}, which is exactly the "a reply is never earlier than its
 * parent" invariant the task brief requires.
 *
 * <p><b>Soft-deleted subtrees</b>: at least {@value #MIN_SOFT_DELETED_SUBTREES} whole subtrees
 * (root plus every descendant reply) are marked {@code deleted_at} in one pass, matching {@code
 * CommentRepository.softDeleteSubtree}'s tombstone shape - the root and every reply beneath it
 * carry the same {@code deleted_at}, computed via {@link SeedTimeline#commentDeletedAt(Instant)} so
 * it can never predate the subtree's own newest activity.
 *
 * <p><b>Filtered comments</b>: the entries of {@code safety/interaction_control.json}'s {@code
 * hidden_comments} are written last, as the comments a post owner's Hidden Words or interaction
 * limit filtered at write time ({@code moderation_status = 'hidden'} with the matching {@code
 * hidden_reason}) and the ones the owner has since released (stored approved). A reply to a hidden
 * comment is hidden with {@code hidden_parent}. The triggers leave a hidden row out of {@code
 * posts.comment_count} and its parent's {@code reply_count}, exactly as they do for one written
 * through the service.
 *
 * <p>Never writes {@code like_count} or {@code reply_count} - both are trigger-maintained (see
 * {@code comment/DATA_RULES.md} Section 2), so this writer's {@code INSERT} statement omits them
 * entirely and lets the column {@code DEFAULT 0} stand until {@link EngagementSeedWriter} runs.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class CommentSeedWriter {

    // Deliberately separate from SeedTimeline's own Random stream (used only for timestamps): this
    // stream drives author selection, pool-entry selection, per-post comment counts, and which
    // subtrees get soft-deleted.
    private static final long COMMENT_RANDOM_SEED = 5_913_071L;

    private static final String PUBLISHED_STATUS = "published";
    private static final String TOP_LEVEL_ONLY_SUITABILITY = "top_level";
    private static final String REPLY_ONLY_SUITABILITY = "reply";
    private static final int MIN_SOFT_DELETED_SUBTREES = 10;
    private static final double REPLY_BAIT_ATTACH_PROBABILITY = 0.7;
    private static final double ORDINARY_ATTACH_PROBABILITY = 0.25;
    private static final int MAX_REPLIES_PER_ROOT = 3;

    private static final Map<String, int[]> ROOT_COMMENT_COUNT_RANGE_BY_BAND =
            Map.of(
                    "low", new int[] {0, 4},
                    "medium", new int[] {2, 7},
                    "high", new int[] {4, 13},
                    "viral", new int[] {8, 26});

    private static final String INSERT_COMMENT_SQL =
            "INSERT INTO comments (id, post_id, user_id, parent_id, root_id, depth, content,"
                    + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SOFT_DELETE_SUBTREE_SQL =
            "UPDATE comments SET deleted_at = ? WHERE id = ?";
    private static final String INSERT_FILTERED_COMMENT_SQL =
            "INSERT INTO comments (id, post_id, user_id, parent_id, root_id, depth, content,"
                    + " moderation_status, hidden_reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?)";

    private final JdbcTemplate jdbc;
    private final InteractionSignalLedger signalLedger;

    /**
     * Inserts every scripted reply chain and a per-post batch of pooled standalone comments (and
     * their occasional replies), then soft-deletes at least {@value #MIN_SOFT_DELETED_SUBTREES}
     * whole subtrees, and returns every generated comment id (including soft-deleted ones) for Task
     * 7's moderation cross-reference.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @param postIdBySeedId seed-id-to-generated-id map produced by {@link PostSeedWriter#write}
     * @return every generated {@code comments.id}, in insertion order, the filtered comments last
     */
    public List<UUID> write(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedTimeline timeline) {
        Map<UUID, Instant> postCreatedAtById = fetchPostCreatedAtById();
        List<PostSeed> eligiblePosts =
                content.posts().stream().filter(p -> PUBLISHED_STATUS.equals(p.status())).toList();

        Map<String, List<PostSeed>> postsByTopicTag = new HashMap<>();
        for (PostSeed post : eligiblePosts) {
            for (String tag : post.topicTags()) {
                postsByTopicTag.computeIfAbsent(tag, key -> new ArrayList<>()).add(post);
            }
        }

        Random random = new Random(COMMENT_RANDOM_SEED);
        Map<String, List<CommentPoolEntry>> pools = content.commentPools().pools();
        List<UserSeed> users = content.users();

        List<Object[]> insertRows = new ArrayList<>();
        List<CommentRow> generated = new ArrayList<>();

        writeChains(
                content.commentPools().conversationSeeds(),
                postsByTopicTag,
                postIdBySeedId,
                postCreatedAtById,
                usersByUsername,
                random,
                timeline,
                insertRows,
                generated);
        writePooledComments(
                eligiblePosts,
                pools,
                postIdBySeedId,
                postCreatedAtById,
                usersByUsername,
                users,
                random,
                timeline,
                insertRows,
                generated);

        jdbc.batchUpdate(INSERT_COMMENT_SQL, insertRows, insertRows.size(), this::bindCommentRow);
        log.info("[seed] comments: {} rows written", insertRows.size());

        int softDeletedSubtrees = softDeleteSubtrees(generated, random, timeline);
        log.info("[seed] comments: {} subtrees soft-deleted", softDeletedSubtrees);

        generated.addAll(
                writeFilteredComments(
                        content, usersByUsername, postIdBySeedId, postCreatedAtById, timeline));

        return generated.stream().map(CommentRow::id).collect(Collectors.toList());
    }

    // Each of the 82 scripted chains is grafted onto one randomly-chosen eligible post whose
    // topic_tags include the chain's own topic_tag (validated non-empty by
    // SeedDataLoaderRealDataTest
    // territory - every chain topic has at least one matching published post in the real fixture).
    // Turn 0 becomes the root (self-referencing root_id); every later turn replies to the turn
    // directly above it, inheriting the same root_id, so the chain reproduces a linear reply
    // thread.
    private void writeChains(
            List<CommentChainSeed> chains,
            Map<String, List<PostSeed>> postsByTopicTag,
            Map<String, UUID> postIdBySeedId,
            Map<UUID, Instant> postCreatedAtById,
            Map<String, UUID> usersByUsername,
            Random random,
            SeedTimeline timeline,
            List<Object[]> insertRows,
            List<CommentRow> generated) {
        for (CommentChainSeed chain : chains) {
            List<PostSeed> candidates = postsByTopicTag.get(chain.topicTag());
            if (candidates == null || candidates.isEmpty()) {
                log.warn(
                        "[seed] comments: chain '{}' has topic_tag '{}' with no eligible published"
                                + " post; skipped",
                        chain.id(),
                        chain.topicTag());
                continue;
            }
            PostSeed targetPost = candidates.get(random.nextInt(candidates.size()));
            UUID postId = postIdBySeedId.get(targetPost.id());
            Instant postCreatedAt = postCreatedAtById.get(postId);

            UUID rootId = null;
            UUID previousId = null;
            Instant previousCreatedAt = postCreatedAt;
            List<CommentChainTurn> turns = chain.turns();
            for (int depth = 0; depth < turns.size(); depth++) {
                CommentChainTurn turn = turns.get(depth);
                UUID authorId = usersByUsername.get(turn.speakerUsername());
                if (authorId == null) {
                    throw new IllegalStateException(
                            "CommentSeedWriter: chain '"
                                    + chain.id()
                                    + "' has speaker_username '"
                                    + turn.speakerUsername()
                                    + "' which does not exist in users.json");
                }
                UUID commentId = UUID.randomUUID();
                Instant createdAt = timeline.commentCreatedAt(previousCreatedAt);
                UUID resolvedRootId = depth == 0 ? commentId : rootId;

                insertRows.add(
                        rowOf(
                                commentId,
                                postId,
                                authorId,
                                depth == 0 ? null : previousId,
                                resolvedRootId,
                                depth,
                                turn.text(),
                                createdAt));
                generated.add(new CommentRow(commentId, resolvedRootId, createdAt));

                if (depth == 0) {
                    rootId = commentId;
                }
                previousId = commentId;
                previousCreatedAt = createdAt;
            }
        }
    }

    // For every eligible post, draws a band-correlated number of pooled standalone root comments
    // (topic-matched across every topic_tag the post carries), and occasionally attaches 1-3 pooled
    // replies beneath a root, favoring roots whose pool entry is marked is_reply_bait.
    private void writePooledComments(
            List<PostSeed> eligiblePosts,
            Map<String, List<CommentPoolEntry>> pools,
            Map<String, UUID> postIdBySeedId,
            Map<UUID, Instant> postCreatedAtById,
            Map<String, UUID> usersByUsername,
            List<UserSeed> users,
            Random random,
            SeedTimeline timeline,
            List<Object[]> insertRows,
            List<CommentRow> generated) {
        for (PostSeed post : eligiblePosts) {
            List<CommentPoolEntry> topicPool = combinedPool(pools, post.topicTags());
            if (topicPool.isEmpty()) {
                continue;
            }
            UUID postId = postIdBySeedId.get(post.id());
            Instant postCreatedAt = postCreatedAtById.get(postId);

            int rootCount = randomRootCount(post.engagementBand(), random);
            for (int i = 0; i < rootCount; i++) {
                CommentPoolEntry rootEntry = pickEntry(topicPool, random, false);
                if (rootEntry == null) {
                    continue;
                }
                UUID authorId = randomUser(users, usersByUsername, random);
                UUID rootCommentId = UUID.randomUUID();
                Instant rootCreatedAt = timeline.commentCreatedAt(postCreatedAt);

                insertRows.add(
                        rowOf(
                                rootCommentId,
                                postId,
                                authorId,
                                null,
                                rootCommentId,
                                0,
                                rootEntry.text(),
                                rootCreatedAt));
                generated.add(new CommentRow(rootCommentId, rootCommentId, rootCreatedAt));

                double attachProbability =
                        rootEntry.isReplyBait()
                                ? REPLY_BAIT_ATTACH_PROBABILITY
                                : ORDINARY_ATTACH_PROBABILITY;
                if (random.nextDouble() >= attachProbability) {
                    continue;
                }
                attachReplies(
                        topicPool,
                        postId,
                        rootCommentId,
                        rootCreatedAt,
                        usersByUsername,
                        users,
                        random,
                        timeline,
                        insertRows,
                        generated);
            }
        }
    }

    private void attachReplies(
            List<CommentPoolEntry> topicPool,
            UUID postId,
            UUID rootCommentId,
            Instant rootCreatedAt,
            Map<String, UUID> usersByUsername,
            List<UserSeed> users,
            Random random,
            SeedTimeline timeline,
            List<Object[]> insertRows,
            List<CommentRow> generated) {
        int replyCount = 1 + random.nextInt(MAX_REPLIES_PER_ROOT);
        UUID parentId = rootCommentId;
        Instant parentCreatedAt = rootCreatedAt;
        for (int depth = 1; depth <= replyCount; depth++) {
            CommentPoolEntry replyEntry = pickEntry(topicPool, random, true);
            if (replyEntry == null) {
                break;
            }
            UUID replyAuthor = randomUser(users, usersByUsername, random);
            UUID replyId = UUID.randomUUID();
            Instant replyCreatedAt = timeline.commentCreatedAt(parentCreatedAt);

            insertRows.add(
                    rowOf(
                            replyId,
                            postId,
                            replyAuthor,
                            parentId,
                            rootCommentId,
                            depth,
                            replyEntry.text(),
                            replyCreatedAt));
            generated.add(new CommentRow(replyId, rootCommentId, replyCreatedAt));

            parentId = replyId;
            parentCreatedAt = replyCreatedAt;
        }
    }

    // Picks MIN_SOFT_DELETED_SUBTREES distinct subtrees (grouped by their shared root id) in a
    // deterministic shuffle order and marks the root plus every descendant with the same
    // deleted_at, matching CommentRepository.softDeleteSubtree's tombstone shape.
    private int softDeleteSubtrees(
            List<CommentRow> generated, Random random, SeedTimeline timeline) {
        Map<UUID, List<CommentRow>> bySubtreeRoot =
                generated.stream().collect(Collectors.groupingBy(CommentRow::subtreeRootId));
        List<UUID> subtreeRootIds = new ArrayList<>(bySubtreeRoot.keySet());
        Collections.shuffle(subtreeRootIds, random);

        List<Object[]> updateRows = new ArrayList<>();
        int softDeletedCount = 0;
        for (UUID subtreeRootId : subtreeRootIds) {
            if (softDeletedCount >= MIN_SOFT_DELETED_SUBTREES) {
                break;
            }
            List<CommentRow> subtree = bySubtreeRoot.get(subtreeRootId);
            Instant latestActivity =
                    subtree.stream()
                            .map(CommentRow::createdAt)
                            .max(Instant::compareTo)
                            .orElseThrow();
            Instant deletedAt = timeline.commentDeletedAt(latestActivity);
            for (CommentRow row : subtree) {
                updateRows.add(new Object[] {Timestamp.from(deletedAt), row.id()});
            }
            softDeletedCount++;
        }
        jdbc.batchUpdate(
                SOFT_DELETE_SUBTREE_SQL, updateRows, updateRows.size(), this::bindSoftDeleteRow);
        return softDeletedCount;
    }

    // Written after the soft-deletes so no filtered comment can fall inside a deleted subtree, and
    // in file order so a hidden_parent reply always finds its parent already inserted.
    private List<CommentRow> writeFilteredComments(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            Map<UUID, Instant> postCreatedAtById,
            SeedTimeline timeline) {
        Map<String, List<PostSeed>> publishedByAuthor = new HashMap<>();
        for (PostSeed post : content.posts()) {
            if (PUBLISHED_STATUS.equals(post.status())) {
                publishedByAuthor
                        .computeIfAbsent(post.authorUsername(), key -> new ArrayList<>())
                        .add(post);
            }
        }
        Map<String, CommentRow> writtenBySeedId = new HashMap<>();
        Map<String, Integer> depthBySeedId = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        List<CommentRow> written = new ArrayList<>();
        for (HiddenCommentSeed seed : content.interactionControl().hiddenComments()) {
            PostSeed post = publishedByAuthor.get(seed.postOwner()).get(seed.postIndex());
            UUID postId = postIdBySeedId.get(post.id());
            UUID authorId = usersByUsername.get(seed.commenter());
            if (postId == null || authorId == null) {
                throw new IllegalStateException(
                        "CommentSeedWriter: hidden_comments entry '"
                                + seed.id()
                                + "' names a post or commenter that was not written");
            }
            CommentRow parent = seed.replyTo() == null ? null : writtenBySeedId.get(seed.replyTo());
            Instant createdAt =
                    timeline.commentCreatedAt(
                            parent == null ? postCreatedAtById.get(postId) : parent.createdAt());
            UUID id = UUID.randomUUID();
            CommentRow row =
                    new CommentRow(id, parent == null ? id : parent.subtreeRootId(), createdAt);
            int depth = parent == null ? 0 : depthBySeedId.get(seed.replyTo()) + 1;
            writtenBySeedId.put(seed.id(), row);
            depthBySeedId.put(seed.id(), depth);
            written.add(row);
            recordSignals(
                    seed, id, authorId, usersByUsername.get(seed.postOwner()), createdAt, timeline);
            rows.add(
                    new Object[] {
                        id,
                        postId,
                        authorId,
                        parent == null ? null : parent.id(),
                        row.subtreeRootId(),
                        (short) depth,
                        seed.text(),
                        seed.unhidden() ? "approved" : "hidden",
                        seed.unhidden() ? null : seed.reason(),
                        Timestamp.from(createdAt)
                    });
        }
        jdbc.batchUpdate(
                INSERT_FILTERED_COMMENT_SQL, rows, rows.size(), this::bindFilteredCommentRow);
        log.info("[seed] comments: {} filtered comments written", rows.size());
        return written;
    }

    // A comment the rules hid was announced as hidden when it was written, and one the owner then
    // released was announced again when the owner unhid it, a day later. A released comment is
    // stored approved, so the ledger also remembers it for the notification writer.
    private void recordSignals(
            HiddenCommentSeed seed,
            UUID commentId,
            UUID authorId,
            UUID postOwnerId,
            Instant createdAt,
            SeedTimeline timeline) {
        signalLedger.record(
                new UserEventImport(
                        InteractionSignalLedger.signalId("comment_hidden", seed.id()),
                        authorId,
                        UserEventType.COMMENT_HIDDEN,
                        "comment",
                        commentId,
                        "{\"reason\":\"" + seed.reason() + "\"}",
                        createdAt.plusSeconds(1)));
        if (seed.unhidden()) {
            Instant unhiddenAt = createdAt.plus(Duration.ofDays(1));
            signalLedger.record(
                    new UserEventImport(
                            InteractionSignalLedger.signalId("comment_unhide", seed.id()),
                            postOwnerId,
                            UserEventType.COMMENT_UNHIDE,
                            "comment",
                            commentId,
                            unhiddenAt.isAfter(timeline.referenceNow())
                                    ? timeline.referenceNow()
                                    : unhiddenAt));
            signalLedger.markUnhidden(commentId);
        }
    }

    private void bindFilteredCommentRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
        if (row[3] == null) {
            ps.setNull(4, Types.OTHER);
        } else {
            ps.setObject(4, row[3]);
        }
        ps.setObject(5, row[4]);
        ps.setShort(6, (Short) row[5]);
        ps.setString(7, (String) row[6]);
        ps.setString(8, (String) row[7]);
        if (row[8] == null) {
            ps.setNull(9, Types.VARCHAR);
        } else {
            ps.setString(9, (String) row[8]);
        }
        ps.setTimestamp(10, (Timestamp) row[9]);
    }

    private Map<UUID, Instant> fetchPostCreatedAtById() {
        Map<UUID, Instant> createdAtByPostId = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM posts",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    createdAtByPostId.put(id, createdAt);
                });
        return createdAtByPostId;
    }

    private List<CommentPoolEntry> combinedPool(
            Map<String, List<CommentPoolEntry>> pools, List<String> topicTags) {
        List<CommentPoolEntry> combined = new ArrayList<>();
        for (String tag : topicTags) {
            List<CommentPoolEntry> entries = pools.get(tag);
            if (entries != null) {
                combined.addAll(entries);
            }
        }
        return combined;
    }

    // depth_suitability partitions the pool into "either" (usable at any depth), "top_level"
    // (root only) and "reply" (reply only). A root draw excludes reply-only entries; a reply draw
    // excludes top_level-only entries.
    private CommentPoolEntry pickEntry(
            List<CommentPoolEntry> pool, Random random, boolean forReply) {
        List<CommentPoolEntry> filtered = new ArrayList<>();
        for (CommentPoolEntry entry : pool) {
            String suitability = entry.depthSuitability();
            boolean excluded =
                    forReply
                            ? TOP_LEVEL_ONLY_SUITABILITY.equals(suitability)
                            : REPLY_ONLY_SUITABILITY.equals(suitability);
            if (!excluded) {
                filtered.add(entry);
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        return filtered.get(random.nextInt(filtered.size()));
    }

    private int randomRootCount(String engagementBand, Random random) {
        int[] range =
                ROOT_COMMENT_COUNT_RANGE_BY_BAND.getOrDefault(
                        engagementBand, ROOT_COMMENT_COUNT_RANGE_BY_BAND.get("low"));
        int span = range[1] - range[0];
        return span > 0 ? range[0] + random.nextInt(span) : range[0];
    }

    private UUID randomUser(
            List<UserSeed> users, Map<String, UUID> usersByUsername, Random random) {
        UserSeed picked = users.get(random.nextInt(users.size()));
        return usersByUsername.get(picked.username());
    }

    private Object[] rowOf(
            UUID id,
            UUID postId,
            UUID authorId,
            UUID parentId,
            UUID rootId,
            int depth,
            String content,
            Instant createdAt) {
        return new Object[] {
            id,
            postId,
            authorId,
            parentId,
            rootId,
            (short) depth,
            content,
            Timestamp.from(createdAt)
        };
    }

    private void bindCommentRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
        if (row[3] == null) {
            ps.setNull(4, Types.OTHER);
        } else {
            ps.setObject(4, row[3]);
        }
        ps.setObject(5, row[4]);
        ps.setShort(6, (Short) row[5]);
        ps.setString(7, (String) row[6]);
        ps.setTimestamp(8, (Timestamp) row[7]);
    }

    private void bindSoftDeleteRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setTimestamp(1, (Timestamp) row[0]);
        ps.setObject(2, row[1]);
    }

    // Tracks just enough about each generated comment (its id, the id of its subtree's root, and
    // its own created_at) to select whole subtrees for soft-delete after the batch insert.
    private record CommentRow(UUID id, UUID subtreeRootId, Instant createdAt) {}
}
