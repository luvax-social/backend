package com.app.common.seed.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.PersonaSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.UserSeed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Emits a bounded set of real domain events through the transactional outbox for every row {@link
 * PostSeedWriter}, {@link CommentSeedWriter} and {@link EngagementSeedWriter} have already written,
 * so the real Elasticsearch and Gorse consumers can index and learn from the seeded database.
 *
 * <p>Reads every id it needs back from the database rather than accepting id collections as
 * parameters: {@link PostSeedWriter#write} only returns its seed-id-to-post-id map (hashtag ids
 * stay internal), and {@link EngagementSeedWriter#write} returns {@code void}. Reading rows back
 * after every domain writer has run is the same pattern {@link CommentSeedWriter} and {@link
 * EngagementSeedWriter} already use to resolve state a prior writer did not expose.
 *
 * <p><b>{@code post.viewed.v1} is emitted</b>, and this module is now its only producer during a
 * seed. The recommender's factorization machine ranker trains read feedback as its negative
 * examples, so a seeded database with no read signal teaches it nothing; {@link
 * AnalyticsSeedWriter} therefore no longer writes {@code post_view} rows of its own, and the same
 * logical view cannot produce two rows. Emitting these events still never touches {@code
 * posts.view_count}, which {@link PostSeedWriter} sets directly and which no application code
 * writes.
 *
 * <p>The view set is built so the signal is learnable rather than uniform noise. It is the union of
 * every {@code post_likes} and {@code post_saves} pair, which makes a user's read set a superset of
 * what they liked or saved because reading precedes liking, plus extra viewers drawn per post in
 * proportion to that post's engagement band and restricted to users whose persona topics intersect
 * the post's topics. A uniform random draw would leave every user looking identical to
 * collaborative filtering, and an unweighted one would make the trending score meaningless by
 * giving a low-band post as many views as a viral-band one.
 *
 * <p>Enqueuing happens through the real {@code OutboxService} bean (wrapped in {@link
 * SeedOutboxBatchWriter}'s own {@code @Transactional} methods), which guarantees the payload always
 * matches {@code DomainEventEnvelopeJson}'s exact shape because it is the same production code path
 * every real write uses.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class SeedOutboxEmitter {

    // A few hundred enqueue calls per transaction, not one transaction for the whole ~21,000+ event
    // volume, so the emission phase never holds locks for its full duration.
    private static final int BATCH_SIZE = 300;

    // Total post.viewed.v1 events a full seed emits, distributed across published posts in
    // proportion to their engagement band. Sized to stay the same order of magnitude as the
    // existing like volume so the outbox publisher drains the run in minutes, not hours.
    private static final int VIEW_EVENT_TARGET = 12_000;

    // The same band weights EngagementSeedWriter applies to likes and saves, so the view, like and
    // save signals agree on which posts are popular instead of contradicting one another.
    private static final Map<String, Integer> VIEW_WEIGHT_BY_BAND =
            Map.of("low", 1, "medium", 3, "high", 8, "viral", 20);

    private static final long VIEW_RANDOM_SEED = 5_512_907L;

    private static final String PUBLISHED_STATUS_FILTER =
            "SELECT id, user_id, created_at FROM posts WHERE status = 'published'::post_status";

    // Every post, not only the published ones. The index-sync consumer derives the recommender
    // item's IsHidden flag from the post's real status, so a non-published post that never
    // receives an upsert event is not merely missing from the recommender - Gorse's
    // auto_insert_item creates it anyway the first time any feedback references it, unlabelled
    // and visible. Measured before this filter existed: 19 non-published posts were live items
    // with IsHidden false. The Elasticsearch half of that consumer still drops them.
    private static final String ALL_POSTS_FILTER = "SELECT id, user_id, created_at FROM posts";

    private static final String POST_SHARE_MESSAGES_SQL =
            "SELECT m.id AS message_id, m.shared_post_id, m.sender_id, p.user_id AS post_owner_id"
                    + " FROM messages m JOIN posts p ON p.id = m.shared_post_id"
                    + " WHERE m.message_type = 'post_share'::message_type"
                    + " AND m.shared_post_id IS NOT NULL AND m.sender_id IS NOT NULL";

    private final JdbcTemplate jdbc;
    private final SeedOutboxBatchWriter batchWriter;

    /**
     * One published post's identifying data needed to build a {@code post.index.upsert.v1} payload.
     */
    public record PostIndexRow(
            UUID postId, UUID userId, List<String> hashtagIds, Instant createdAt) {}

    /** One {@code post_likes}/{@code post_saves} row's identifying data. */
    public record ReactionRow(UUID postId, UUID postOwnerId, UUID userId) {}

    /** One seeded post-share message's identifying data. */
    public record ShareRow(UUID postId, UUID postOwnerId, UUID senderId, UUID messageId) {}

    /** One non-soft-deleted comment row's identifying data. */
    public record CommentRow(
            UUID commentId,
            UUID postId,
            UUID postOwnerId,
            UUID userId,
            int depth,
            UUID parentOwnerId) {}

    /** One real sample of each of the five emitted event kinds, used by the envelope proof. */
    public record ProofSample(
            PostIndexRow post,
            UUID hashtagId,
            ReactionRow like,
            ReactionRow save,
            CommentRow comment) {}

    /** Per-event-type counts of what {@link #emitFullVolume()} actually enqueued. */
    public record EmissionCounts(
            int postIndex,
            int hashtagIndex,
            int likes,
            int saves,
            int comments,
            int views,
            int shares) {
        public int total() {
            return postIndex + hashtagIndex + likes + saves + comments + views + shares;
        }
    }

    /**
     * Emits exactly one real event of each of the five seed-emitted event types, sampled from
     * whatever the database currently holds.
     *
     * <p>This is the mandatory envelope-correctness gate: only after these five events drain
     * cleanly through the real outbox publisher and real consumers should {@link #emitFullVolume()}
     * run.
     *
     * @return the sample rows the proof slice was built from, for assertions and logging
     * @throws IllegalStateException if the database does not yet contain at least one row of every
     *     required kind - every domain writer must run before this method is called
     */
    public ProofSample emitProofSlice() {
        List<PostIndexRow> posts = fetchPublishedPosts();
        requireNonEmpty(posts, "published posts");
        List<UUID> hashtagIds = fetchAllHashtagIds();
        requireNonEmpty(hashtagIds, "hashtags");
        List<ReactionRow> likes = fetchPostLikes();
        requireNonEmpty(likes, "post_likes rows");
        List<ReactionRow> saves = fetchPostSaves();
        requireNonEmpty(saves, "post_saves rows");
        List<CommentRow> comments = fetchComments();
        requireNonEmpty(comments, "comments");

        ProofSample sample =
                new ProofSample(
                        posts.get(0),
                        hashtagIds.get(0),
                        likes.get(0),
                        saves.get(0),
                        comments.get(0));
        batchWriter.emitProof(sample);
        log.info(
                "[seed] outbox proof slice emitted: post={}, hashtag={}, like.post={}, save.post={},"
                        + " comment={}",
                sample.post().postId(),
                sample.hashtagId(),
                sample.like().postId(),
                sample.save().postId(),
                sample.comment().commentId());
        return sample;
    }

    /**
     * Emits the full seed event volume through the real transactional outbox: one {@code
     * post.index.upsert.v1} per published post, one {@code hashtag.index.upsert.v1} per hashtag
     * row, one {@code post.liked.v1} per {@code post_likes} row, one {@code post.saved.v1} per
     * {@code post_saves} row, and one {@code comment.created.v1} per non-soft-deleted comment.
     *
     * @return the number of events enqueued per type
     */
    public EmissionCounts emitFullVolume(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId) {
        List<PostIndexRow> posts = fetchAllPosts();
        List<UUID> hashtagIds = fetchAllHashtagIds();
        List<ReactionRow> likes = fetchPostLikes();
        List<ReactionRow> saves = fetchPostSaves();
        List<CommentRow> comments = fetchComments();

        emitBatched(posts, batchWriter::emitPostIndexBatch);
        emitBatched(hashtagIds, batchWriter::emitHashtagIndexBatch);
        emitBatched(likes, batchWriter::emitLikeBatch);
        emitBatched(saves, batchWriter::emitSaveBatch);
        emitBatched(comments, batchWriter::emitCommentBatch);

        List<ReactionRow> views = buildViewRows(content, usersByUsername, postIdBySeedId);
        emitBatched(views, batchWriter::emitViewBatch);

        List<ShareRow> shares = fetchPostShares();
        emitBatched(shares, batchWriter::emitShareBatch);

        EmissionCounts counts =
                new EmissionCounts(
                        posts.size(),
                        hashtagIds.size(),
                        likes.size(),
                        saves.size(),
                        comments.size(),
                        views.size(),
                        shares.size());
        log.info(
                "[seed] outbox full volume emitted: postIndex={}, hashtagIndex={}, likes={}, saves={},"
                        + " comments={}, views={}, shares={}, total={}",
                counts.postIndex(),
                counts.hashtagIndex(),
                counts.likes(),
                counts.saves(),
                counts.comments(),
                counts.views(),
                counts.shares(),
                counts.total());
        return counts;
    }

    // The read set is the union of every like and save pair with a band-weighted, persona-matched
    // draw. Deduplicated on (user, post), so one logical view never yields two events no matter how
    // many times a pair is reachable.
    private List<ReactionRow> buildViewRows(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId) {
        Map<UUID, UUID> ownerByPostId = fetchPostOwners();
        Set<String> seenPairs = new LinkedHashSet<>();
        List<ReactionRow> views = new ArrayList<>();

        // Reading precedes liking, so every like and save pair must already be a read pair.
        for (ReactionRow reaction : fetchPostLikes()) {
            addView(views, seenPairs, reaction.postId(), reaction.postOwnerId(), reaction.userId());
        }
        for (ReactionRow reaction : fetchPostSaves()) {
            addView(views, seenPairs, reaction.postId(), reaction.postOwnerId(), reaction.userId());
        }

        Map<UUID, Set<String>> topicsByUserId = new HashMap<>();
        List<UUID> allUserIds = new ArrayList<>();
        indexUserTopics(content, usersByUsername, topicsByUserId, allUserIds);

        List<PostSeed> published =
                content.posts().stream()
                        .filter(post -> "published".equals(post.status()))
                        .filter(post -> postIdBySeedId.containsKey(post.id()))
                        .toList();
        int totalWeight = 0;
        for (PostSeed post : published) {
            totalWeight += VIEW_WEIGHT_BY_BAND.getOrDefault(post.engagementBand(), 1);
        }
        if (totalWeight == 0) {
            return views;
        }

        Random random = new Random(VIEW_RANDOM_SEED);
        for (PostSeed post : published) {
            UUID postId = postIdBySeedId.get(post.id());
            UUID ownerId = ownerByPostId.get(postId);
            if (ownerId == null) {
                continue;
            }
            int weight = VIEW_WEIGHT_BY_BAND.getOrDefault(post.engagementBand(), 1);
            int target =
                    Math.max(
                            2, (int) Math.round((double) VIEW_EVENT_TARGET * weight / totalWeight));
            List<UUID> candidates = candidateViewers(post, ownerId, topicsByUserId, allUserIds);
            if (candidates.isEmpty()) {
                continue;
            }
            int added = 0;
            for (int attempt = 0; added < target && attempt < target * 4; attempt++) {
                UUID viewerId = candidates.get(random.nextInt(candidates.size()));
                if (addView(views, seenPairs, postId, ownerId, viewerId)) {
                    added++;
                }
            }
        }
        return views;
    }

    private void indexUserTopics(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<UUID, Set<String>> topicsByUserId,
            List<UUID> allUserIds) {
        Map<String, List<String>> topicsByPersonaId = new HashMap<>();
        for (PersonaSeed persona : content.personas()) {
            topicsByPersonaId.put(
                    persona.id(), persona.topics() == null ? List.of() : persona.topics());
        }
        for (UserSeed user : content.users()) {
            UUID userId = usersByUsername.get(user.username());
            if (userId == null) {
                continue;
            }
            allUserIds.add(userId);
            topicsByUserId.put(
                    userId,
                    new LinkedHashSet<>(
                            topicsByPersonaId.getOrDefault(user.personaId(), List.of())));
        }
    }

    // Viewers whose persona topics intersect the post's own topics, so a user's reads cluster the
    // way their persona does and collaborative filtering has structure to learn. A post no persona
    // matches falls back to the whole population rather than receiving no views at all.
    private List<UUID> candidateViewers(
            PostSeed post,
            UUID ownerId,
            Map<UUID, Set<String>> topicsByUserId,
            List<UUID> allUserIds) {
        List<String> postTopics = post.topicTags() == null ? List.of() : post.topicTags();
        List<UUID> matched = new ArrayList<>();
        for (Map.Entry<UUID, Set<String>> entry : topicsByUserId.entrySet()) {
            if (entry.getKey().equals(ownerId)) {
                continue;
            }
            for (String topic : postTopics) {
                if (entry.getValue().contains(topic)) {
                    matched.add(entry.getKey());
                    break;
                }
            }
        }
        if (!matched.isEmpty()) {
            return matched;
        }
        return allUserIds.stream().filter(id -> !id.equals(ownerId)).toList();
    }

    // A self-view is never recorded, matching PostViewServiceImpl, which accepts a post owner's own
    // view but never emits an event for it.
    private boolean addView(
            List<ReactionRow> views,
            Set<String> seenPairs,
            UUID postId,
            UUID ownerId,
            UUID viewerId) {
        if (viewerId.equals(ownerId) || !seenPairs.add(viewerId + "::" + postId)) {
            return false;
        }
        views.add(new ReactionRow(postId, ownerId, viewerId));
        return true;
    }

    private Map<UUID, UUID> fetchPostOwners() {
        Map<UUID, UUID> owners = new HashMap<>();
        jdbc.query(
                PUBLISHED_STATUS_FILTER,
                (java.sql.ResultSet rs) -> {
                    owners.put((UUID) rs.getObject("id"), (UUID) rs.getObject("user_id"));
                });
        return owners;
    }

    // Sharing a post is implemented as sending it inside a conversation, and MessageSeedWriter
    // writes those rows straight to the database rather than through MessageServiceImpl, so the
    // real post.shared.v1 producer never runs during a seed. Without this the share feedback
    // bucket is empty and "share" sits in positive_feedback_types with nothing in it.
    private List<ShareRow> fetchPostShares() {
        return jdbc.query(
                POST_SHARE_MESSAGES_SQL,
                (rs, rowNum) ->
                        new ShareRow(
                                (UUID) rs.getObject("shared_post_id"),
                                (UUID) rs.getObject("post_owner_id"),
                                (UUID) rs.getObject("sender_id"),
                                (UUID) rs.getObject("message_id")));
    }

    private void requireNonEmpty(List<?> rows, String description) {
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "SeedOutboxEmitter: no "
                            + description
                            + " found - every domain writer must run before emitting outbox events");
        }
    }

    // Each batch is handed to SeedOutboxBatchWriter, a separate injected bean: the call below goes
    // through that bean's own Spring proxy, so its @Transactional per-batch methods actually open a
    // new transaction per batch. Calling a @Transactional method on `this` instead would bypass the
    // proxy (self-invocation) and silently run with no transaction at all.
    private <T> void emitBatched(List<T> items, Consumer<List<T>> emitBatch) {
        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, items.size());
            emitBatch.accept(items.subList(start, end));
        }
    }

    private List<PostIndexRow> fetchAllPosts() {
        return fetchPostIndexRows(ALL_POSTS_FILTER);
    }

    private List<PostIndexRow> fetchPublishedPosts() {
        return fetchPostIndexRows(PUBLISHED_STATUS_FILTER);
    }

    private List<PostIndexRow> fetchPostIndexRows(String sql) {
        Map<UUID, List<String>> hashtagIdsByPostId = fetchHashtagIdsByPostId();
        return jdbc.query(
                sql,
                (rs, rowNum) -> {
                    UUID postId = (UUID) rs.getObject("id");
                    return new PostIndexRow(
                            postId,
                            (UUID) rs.getObject("user_id"),
                            hashtagIdsByPostId.getOrDefault(postId, List.of()),
                            rs.getTimestamp("created_at").toInstant());
                });
    }

    private Map<UUID, List<String>> fetchHashtagIdsByPostId() {
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query(
                "SELECT post_id, hashtag_id FROM post_hashtags",
                rs -> {
                    UUID postId = (UUID) rs.getObject("post_id");
                    String hashtagId = rs.getObject("hashtag_id").toString();
                    result.computeIfAbsent(postId, key -> new ArrayList<>()).add(hashtagId);
                });
        return result;
    }

    private List<UUID> fetchAllHashtagIds() {
        return jdbc.query("SELECT id FROM hashtags", (rs, rowNum) -> (UUID) rs.getObject("id"));
    }

    private List<ReactionRow> fetchPostLikes() {
        return fetchReactions(
                "SELECT pl.post_id, pl.user_id, p.user_id AS post_owner_id FROM post_likes pl JOIN"
                        + " posts p ON p.id = pl.post_id");
    }

    private List<ReactionRow> fetchPostSaves() {
        return fetchReactions(
                "SELECT ps.post_id, ps.user_id, p.user_id AS post_owner_id FROM post_saves ps JOIN"
                        + " posts p ON p.id = ps.post_id");
    }

    private List<ReactionRow> fetchReactions(String sql) {
        return jdbc.query(
                sql,
                (rs, rowNum) ->
                        new ReactionRow(
                                (UUID) rs.getObject("post_id"),
                                (UUID) rs.getObject("post_owner_id"),
                                (UUID) rs.getObject("user_id")));
    }

    private List<CommentRow> fetchComments() {
        String sql =
                "SELECT c.id, c.post_id, c.user_id, c.depth, p.user_id AS post_owner_id, parent.user_id"
                        + " AS parent_owner_id FROM comments c JOIN posts p ON p.id = c.post_id LEFT"
                        + " JOIN comments parent ON parent.id = c.parent_id WHERE c.deleted_at IS"
                        + " NULL AND c.admin_removed_at IS NULL";
        return jdbc.query(
                sql,
                (rs, rowNum) -> {
                    Object parentOwner = rs.getObject("parent_owner_id");
                    return new CommentRow(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("post_id"),
                            (UUID) rs.getObject("post_owner_id"),
                            (UUID) rs.getObject("user_id"),
                            rs.getInt("depth"),
                            parentOwner == null ? null : (UUID) parentOwner);
                });
    }
}
