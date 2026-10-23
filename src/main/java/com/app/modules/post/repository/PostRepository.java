package com.app.modules.post.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {

    Optional<Post> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Loads a live post and locks its row for the rest of the transaction.
     *
     * <p>Every owner change to a post's lifecycle and the scheduled publisher take this lock, so an
     * owner edit, an archive, a reschedule and a due publication serialise on the row instead of
     * racing.
     *
     * @param id post identifier
     * @return the post, locked, when it exists and is not soft-deleted
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Post p WHERE p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Loads posts by id with their media collections fetch-joined.
     *
     * <p>Callers that consume {@code Post.media} outside an open persistence session (detached
     * hydration in ranking pipelines) must use this instead of {@code findAllById}.
     *
     * @param ids post identifiers
     * @return matching non-deleted posts in no particular order
     */
    @Query("SELECT DISTINCT p FROM Post p LEFT JOIN FETCH p.media WHERE p.id IN :ids")
    List<Post> findAllWithMediaByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Reads the author of a post regardless of its soft-delete state.
     *
     * @param postId post identifier
     * @return author identifier when the post exists
     */
    @Query(value = "SELECT user_id FROM posts WHERE id = :postId", nativeQuery = true)
    Optional<UUID> findOwnerIdIncludingDeleted(@Param("postId") UUID postId);

    /**
     * Reads the persisted status of a post regardless of its soft-delete state.
     *
     * @param postId post identifier
     * @return lowercase PostgreSQL status when the post exists
     */
    @Query(value = "SELECT status::text FROM posts WHERE id = :postId", nativeQuery = true)
    Optional<String> findStatusIncludingDeleted(@Param("postId") UUID postId);

    /**
     * Reads everything the moderation removal and restore paths need, regardless of soft-delete.
     *
     * @param postId post identifier
     * @return the moderation view when the post exists
     */
    @Query(
            value =
                    "SELECT user_id AS userId, caption AS caption, created_at AS createdAt,"
                            + " published_at AS publishedAt, scheduled_at AS scheduledAt,"
                            + " place_id AS placeId, status::text AS status,"
                            + " status_before_moderation::text AS statusBeforeModeration"
                            + " FROM posts WHERE id = :postId",
            nativeQuery = true)
    Optional<PostModerationProjection> findModerationViewIncludingDeleted(
            @Param("postId") UUID postId);

    /**
     * Removes a post by moderation, recording the status it held so restore can return it there.
     *
     * @param postId post identifier
     * @param deletedAt soft-delete timestamp
     * @return number of updated posts
     */
    @Modifying
    @Query(
            value =
                    "UPDATE posts SET status_before_moderation = status, status = 'removed',"
                            + " deleted_at = :deletedAt WHERE id = :postId",
            nativeQuery = true)
    int applyModerationRemoval(
            @Param("postId") UUID postId, @Param("deletedAt") OffsetDateTime deletedAt);

    /**
     * Restores a moderation-removed post to the given status and clears the recorded prior status.
     *
     * <p>The scheduled time is kept only when the post returns to {@code scheduled}, because a post
     * that comes back as a draft or published has no use for it. The caller decides the status: a
     * scheduled post whose time has passed is restored as a draft.
     *
     * @param postId post identifier
     * @param status lowercase PostgreSQL post status to restore to
     * @param placeId the place to keep, or null to drop the one the post carried
     * @return number of updated posts
     */
    @Modifying
    @Query(
            value =
                    "UPDATE posts SET status = CAST(:status AS post_status),"
                            + " status_before_moderation = NULL, deleted_at = NULL,"
                            + " place_id = :placeId,"
                            + " scheduled_at = CASE WHEN :status = 'scheduled' THEN scheduled_at"
                            + " ELSE NULL END WHERE id = :postId",
            nativeQuery = true)
    int applyModerationRestore(
            @Param("postId") UUID postId,
            @Param("status") String status,
            @Param("placeId") UUID placeId);

    // The six keyset queries below are each a UNION of two branches, the posts an account owns and
    // the posts it co-authors, every branch ordered and limited on its own. Written as one query
    // with an OR between "owned" and "in the co-author table", the planner cannot use a per-author
    // index for the ordering and falls back to scanning the global publication index and filtering,
    // which for a feed over many followed accounts reads far more rows than a page needs. Each
    // branch here is a plain ordered index scan, and the outer query only merges two short lists.
    String OWN_POSTS =
            "SELECT p.* FROM posts p WHERE p.status = 'published' AND p.deleted_at IS NULL"
                    + " AND p.user_id = :userId";
    String COAUTHORED_POSTS =
            "SELECT p.* FROM posts p JOIN post_collaborators c ON c.post_id = p.id"
                    + " AND c.status = 'accepted' AND c.user_id = :userId"
                    + " WHERE p.status = 'published' AND p.deleted_at IS NULL";
    String OF_TYPES = " AND p.post_type = ANY(CAST(string_to_array(:types, ',') AS post_type[]))";
    String AFTER_CURSOR = " AND (p.published_at, p.id) < (:cursorTime, :cursorId)";
    String NEWEST_FIRST_LIMITED = " ORDER BY p.published_at DESC, p.id DESC LIMIT :limit";
    String MERGED_NEWEST_FIRST_LIMITED = ") t ORDER BY t.published_at DESC, t.id DESC LIMIT :limit";

    String FEED_FILTERS =
            " AND NOT EXISTS (SELECT 1 FROM v_post_authors a WHERE a.post_id = p.id"
                    + " AND a.user_id IN (:excludedAuthorIds))"
                    + " AND NOT EXISTS (SELECT 1 FROM post_not_interested n"
                    + " WHERE n.user_id = :viewerId AND n.post_id = p.id)";
    String FEED_OWN_POSTS =
            "SELECT p.* FROM posts p WHERE p.status = 'published' AND p.deleted_at IS NULL"
                    + " AND p.user_id IN (:authorIds)";
    String FEED_COAUTHORED_POSTS =
            "SELECT p.* FROM posts p JOIN post_collaborators c ON c.post_id = p.id"
                    + " AND c.status = 'accepted' AND c.user_id IN (:authorIds)"
                    + " WHERE p.status = 'published' AND p.deleted_at IS NULL";

    /**
     * First keyset page of a user's published posts, newest first.
     *
     * <p>A user's posts are the ones they own and the ones they co-author after accepting. Paired
     * with {@link #findUserPostsBefore}; the no-cursor variant avoids binding an untyped null
     * cursor. Native so the sibling can use a row-value tuple comparison for an exact index seek,
     * which JPQL cannot express.
     *
     * @param userId post author or accepted co-author
     * @param limit the most rows to return; the caller asks for one more than the page to learn
     *     whether another page exists
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM (("
                            + OWN_POSTS
                            + NEWEST_FIRST_LIMITED
                            + ") UNION ("
                            + COAUTHORED_POSTS
                            + NEWEST_FIRST_LIMITED
                            + ")"
                            + MERGED_NEWEST_FIRST_LIMITED,
            nativeQuery = true)
    List<Post> findFirstUserPosts(@Param("userId") UUID userId, @Param("limit") int limit);

    /**
     * Keyset page of a user's published posts strictly after the cursor tuple, newest first.
     *
     * <p>The {@code (published_at, id)} row-value comparison seeks directly to the cursor position
     * and never drops rows that share a boundary {@code published_at}. Served by {@code
     * idx_posts_user_published_id} for the owned posts and by {@code
     * idx_post_collaborators_user_accepted} for the co-authored ones.
     *
     * @param userId post author or accepted co-author
     * @param cursorTime {@code published_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code published_at}; never null
     * @param limit the most rows to return
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM (("
                            + OWN_POSTS
                            + AFTER_CURSOR
                            + NEWEST_FIRST_LIMITED
                            + ") UNION ("
                            + COAUTHORED_POSTS
                            + AFTER_CURSOR
                            + NEWEST_FIRST_LIMITED
                            + ")"
                            + MERGED_NEWEST_FIRST_LIMITED,
            nativeQuery = true)
    List<Post> findUserPostsBefore(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);

    /**
     * First keyset page of a user's published posts restricted to the given types, newest first.
     *
     * <p>Separate from {@link #findFirstUserPosts} rather than binding an empty list, because an
     * empty {@code IN} list is not valid SQL. The types arrive as one comma-delimited parameter
     * that Postgres expands into a {@code post_type[]}, so the cast lands on the parameter and the
     * comparison stays enum-to-enum. Casting the column to text instead discards its statistics and
     * was measured to replace the index scan with a bitmap heap scan plus a sort.
     *
     * @param userId post author or accepted co-author
     * @param types comma-delimited {@code post_type} names; never empty
     * @param limit the most rows to return
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM (("
                            + OWN_POSTS
                            + OF_TYPES
                            + NEWEST_FIRST_LIMITED
                            + ") UNION ("
                            + COAUTHORED_POSTS
                            + OF_TYPES
                            + NEWEST_FIRST_LIMITED
                            + ")"
                            + MERGED_NEWEST_FIRST_LIMITED,
            nativeQuery = true)
    List<Post> findFirstUserPostsByType(
            @Param("userId") UUID userId, @Param("types") String types, @Param("limit") int limit);

    /**
     * Keyset page of a user's published posts of the given types, strictly after the cursor tuple.
     *
     * <p>Same predicate as {@link #findFirstUserPostsByType} with the row-value cursor comparison
     * added.
     *
     * @param userId post author or accepted co-author
     * @param types comma-delimited {@code post_type} names; never empty
     * @param cursorTime {@code published_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code published_at}; never null
     * @param limit the most rows to return
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM (("
                            + OWN_POSTS
                            + OF_TYPES
                            + AFTER_CURSOR
                            + NEWEST_FIRST_LIMITED
                            + ") UNION ("
                            + COAUTHORED_POSTS
                            + OF_TYPES
                            + AFTER_CURSOR
                            + NEWEST_FIRST_LIMITED
                            + ")"
                            + MERGED_NEWEST_FIRST_LIMITED,
            nativeQuery = true)
    List<Post> findUserPostsByTypeBefore(
            @Param("userId") UUID userId,
            @Param("types") String types,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);

    /**
     * First keyset batch of posts in the given status across all users, newest first.
     *
     * <p>Used by the Elasticsearch index seed runner to start walking the table.
     *
     * @param status status filter; the seed runner indexes published posts only
     * @param pageable batch size carrier
     * @return posts ordered by {@code published_at} descending
     */
    @Query(
            "SELECT p FROM Post p WHERE p.status = :status"
                    + " ORDER BY p.publishedAt DESC, p.id DESC")
    List<Post> findFirstPublished(@Param("status") PostStatus status, Pageable pageable);

    /**
     * Keyset batch of posts in the given status published before the cursor tuple, newest first.
     *
     * @param status status filter; the seed runner indexes published posts only
     * @param cursorTime exclusive upper bound on {@code published_at}; never null
     * @param cursorId tiebreaker on equal {@code published_at}; never null
     * @param pageable batch size carrier
     * @return posts ordered by {@code published_at} descending
     */
    @Query(
            "SELECT p FROM Post p WHERE p.status = :status AND (p.publishedAt < :cursorTime"
                    + " OR (p.publishedAt = :cursorTime AND p.id < :cursorId))"
                    + " ORDER BY p.publishedAt DESC, p.id DESC")
    List<Post> findPublishedBefore(
            @Param("status") PostStatus status,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * First keyset page of published posts by any author in {@code authorIds}, newest first.
     *
     * <p>A post belongs to the feed when its owner or any accepted co-author is in {@code
     * authorIds}, and appears once however many of its authors the viewer follows. {@code
     * excludedAuthorIds} removes a post when any of its authors is one the viewer muted, asked for
     * fewer posts from, or is in a block relationship with, so a co-author's block or mute hides
     * the post even when the author followed is someone else.
     *
     * <p>Posts the viewer marked not interested are left out by an anti-join on the mark's primary
     * key, so a viewer with no marks pays one failed index probe per candidate row.
     *
     * @param authorIds accounts whose posts are eligible to appear; must not be empty
     * @param excludedAuthorIds authors that hide a post when they hold any share of it; never
     *     empty, the nil UUID stands in for none
     * @param viewerId the viewer whose not-interested marks are excluded
     * @param limit the most rows to return; the caller asks for one more than the page
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM (("
                            + FEED_OWN_POSTS
                            + FEED_FILTERS
                            + NEWEST_FIRST_LIMITED
                            + ") UNION ("
                            + FEED_COAUTHORED_POSTS
                            + FEED_FILTERS
                            + NEWEST_FIRST_LIMITED
                            + ")"
                            + MERGED_NEWEST_FIRST_LIMITED,
            nativeQuery = true)
    List<Post> findFirstFeedPosts(
            @Param("authorIds") List<UUID> authorIds,
            @Param("excludedAuthorIds") List<UUID> excludedAuthorIds,
            @Param("viewerId") UUID viewerId,
            @Param("limit") int limit);

    /**
     * Keyset page of published posts by any author in {@code authorIds}, older than the cursor,
     * newest first.
     *
     * <p>Same predicate as {@link #findFirstFeedPosts} with the row-value cursor comparison added.
     *
     * @param authorIds accounts whose posts are eligible to appear; must not be empty
     * @param excludedAuthorIds authors that hide a post when they hold any share of it; never empty
     * @param viewerId the viewer whose not-interested marks are excluded
     * @param cursorTime {@code published_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code published_at}; never null
     * @param limit the most rows to return
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM (("
                            + FEED_OWN_POSTS
                            + AFTER_CURSOR
                            + FEED_FILTERS
                            + NEWEST_FIRST_LIMITED
                            + ") UNION ("
                            + FEED_COAUTHORED_POSTS
                            + AFTER_CURSOR
                            + FEED_FILTERS
                            + NEWEST_FIRST_LIMITED
                            + ")"
                            + MERGED_NEWEST_FIRST_LIMITED,
            nativeQuery = true)
    List<Post> findFeedPostsBefore(
            @Param("authorIds") List<UUID> authorIds,
            @Param("excludedAuthorIds") List<UUID> excludedAuthorIds,
            @Param("viewerId") UUID viewerId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);

    /**
     * Reads the trigger-maintained like counter directly from the database.
     *
     * <p>Bypasses the first-level-cached entity state, which goes stale once {@code
     * trg_post_like_count} fires.
     *
     * @param postId post whose counter is read; the post must exist
     * @return current {@code like_count} value
     */
    @Query("SELECT p.likeCount FROM Post p WHERE p.id = :postId")
    int findLikeCount(@Param("postId") UUID postId);

    /**
     * Published posts carrying a hashtag, newest first, as the PostgreSQL degradation path for the
     * posts-by-hashtag read when the {@code elasticsearchSearch} circuit breaker is open.
     *
     * <p>Carries no hashtag status predicate by design. Banning a term hides the term, never the
     * posts that used it, so a status filter must never reach a query that selects posts - see
     * {@code hashtag/DATA_RULES.md} section 3D. The lifecycle gate lives one layer up, where an
     * unavailable hashtag is refused outright instead of silently narrowing this result.
     *
     * <p>The join is served by {@code idx_post_hashtags_tag (hashtag_id, post_id)}; the {@code
     * (published_at, id)} ordering matches every other recent-posts read so a shared tiebreaker
     * keeps rows that share a {@code published_at} from being dropped between pages.
     *
     * @param hashtagId hashtag whose posts are listed
     * @param pageable offset and limit carrier; supply {@link
     *     com.app.common.pagination.OffsetPageable} so the exact cursor offset is preserved
     * @return published, non-deleted posts ordered by the {@code (published_at, id)} tuple
     *     descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p "
                            + "JOIN post_hashtags ph ON ph.post_id = p.id "
                            + "WHERE ph.hashtag_id = :hashtagId AND p.status = 'published' "
                            + "AND p.deleted_at IS NULL "
                            + "ORDER BY p.published_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findPublishedByHashtagId(@Param("hashtagId") UUID hashtagId, Pageable pageable);

    /**
     * Published posts at a place, newest first, as the PostgreSQL degradation path for the
     * posts-by-place read when the {@code elasticsearchSearch} circuit breaker is open.
     *
     * <p>Carries no place status predicate, for the same reason the hashtag read has none: the
     * lifecycle gate lives one layer up, where an unavailable place is refused outright. Served by
     * {@code idx_posts_place_published}.
     *
     * @param placeId place whose posts are listed
     * @param pageable offset and limit carrier
     * @return published, non-deleted posts ordered by the {@code (published_at, id)} tuple
     *     descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.place_id = :placeId"
                            + " AND p.status = 'published' AND p.deleted_at IS NULL "
                            + "ORDER BY p.published_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findPublishedByPlaceId(@Param("placeId") UUID placeId, Pageable pageable);

    /**
     * First keyset page of the owner's posts in a status, most recently changed first.
     *
     * <p>Serves the draft list and the archive. Served by {@code idx_posts_owner_unpublished}.
     *
     * @param userId the owner
     * @param status lowercase status, {@code draft} or {@code archived}
     * @param pageable page size carrier
     * @return posts ordered by the {@code (updated_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.user_id = :userId"
                            + " AND p.status = CAST(:status AS post_status)"
                            + " AND p.deleted_at IS NULL "
                            + "ORDER BY p.updated_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findFirstOwnerPostsByStatus(
            @Param("userId") UUID userId, @Param("status") String status, Pageable pageable);

    /**
     * Keyset page of the owner's posts in a status, strictly after the cursor tuple.
     *
     * @param userId the owner
     * @param status lowercase status, {@code draft} or {@code archived}
     * @param cursorTime {@code updated_at} of the cursor row; never null
     * @param cursorId id of the cursor row; never null
     * @param pageable page size carrier
     * @return posts ordered by the {@code (updated_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.user_id = :userId"
                            + " AND p.status = CAST(:status AS post_status)"
                            + " AND p.deleted_at IS NULL"
                            + " AND (p.updated_at, p.id) < (:cursorTime, :cursorId) "
                            + "ORDER BY p.updated_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findOwnerPostsByStatusBefore(
            @Param("userId") UUID userId,
            @Param("status") String status,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * First page of the owner's scheduled posts, soonest first.
     *
     * @param userId the owner
     * @param pageable page size carrier
     * @return posts ordered by the {@code (scheduled_at, id)} tuple ascending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.user_id = :userId"
                            + " AND p.status = 'scheduled' AND p.deleted_at IS NULL "
                            + "ORDER BY p.scheduled_at ASC, p.id ASC",
            nativeQuery = true)
    List<Post> findFirstOwnerScheduledPosts(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset page of the owner's scheduled posts, strictly after the cursor tuple.
     *
     * @param userId the owner
     * @param cursorTime {@code scheduled_at} of the cursor row; never null
     * @param cursorId id of the cursor row; never null
     * @param pageable page size carrier
     * @return posts ordered by the {@code (scheduled_at, id)} tuple ascending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.user_id = :userId"
                            + " AND p.status = 'scheduled' AND p.deleted_at IS NULL"
                            + " AND (p.scheduled_at, p.id) > (:cursorTime, :cursorId) "
                            + "ORDER BY p.scheduled_at ASC, p.id ASC",
            nativeQuery = true)
    List<Post> findOwnerScheduledPostsAfter(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Counts the owner's drafts and scheduled posts together, which is what the cap limits.
     *
     * @param userId the owner
     * @return the number of live drafts plus live scheduled posts
     */
    @Query(
            value =
                    "SELECT count(*) FROM posts WHERE user_id = :userId"
                            + " AND status IN ('draft', 'scheduled') AND deleted_at IS NULL",
            nativeQuery = true)
    long countDraftsAndScheduled(@Param("userId") UUID userId);

    /**
     * Takes a transaction-scoped advisory lock on the owner's draft slots.
     *
     * <p>Held from the cap check to commit, so two concurrent creates by one owner cannot both see
     * room for the last slot. Always returns 1; the value only exists to give the statement a
     * result the persistence layer can read.
     *
     * @param userId the owner
     * @return 1
     */
    @Query(
            value =
                    "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended("
                            + "CAST(:userId AS text), 150))) AS l",
            nativeQuery = true)
    int lockOwnerDraftSlots(@Param("userId") UUID userId);

    /**
     * Counts the owner's live posts in one status.
     *
     * @param userId the owner
     * @param status lowercase status
     * @return the count
     */
    @Query(
            value =
                    "SELECT count(*) FROM posts WHERE user_id = :userId"
                            + " AND status = CAST(:status AS post_status) AND deleted_at IS NULL",
            nativeQuery = true)
    long countByOwnerAndStatus(@Param("userId") UUID userId, @Param("status") String status);

    /**
     * Ids of scheduled posts that are due, oldest first.
     *
     * <p>A cheap unlocked read that only decides what to try; the publisher re-checks each
     * candidate under a row lock before it acts. Served by {@code idx_posts_scheduled_due}.
     *
     * @param limit how many ids to return
     * @return due post ids, soonest due first
     */
    @Query(
            value =
                    "SELECT id FROM posts WHERE status = 'scheduled' AND scheduled_at <= now()"
                            + " AND deleted_at IS NULL ORDER BY scheduled_at, id LIMIT :limit",
            nativeQuery = true)
    List<UUID> findDueScheduledIds(@Param("limit") int limit);

    /**
     * Claims one due scheduled post for publication, skipping a row another transaction holds.
     *
     * <p>The predicate is repeated under the lock, so a post an owner just rescheduled, archived or
     * deleted, or a moderator just removed, is not claimed, and a second publisher instance that
     * loses the race gets nothing.
     *
     * @param id the candidate post
     * @return the post, locked, when it is still scheduled and due
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE id = :id AND status = 'scheduled'"
                            + " AND scheduled_at <= now() AND deleted_at IS NULL"
                            + " FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Post> claimDueScheduledPost(@Param("id") UUID id);

    /**
     * First keyset page of published posts in which the user has an approved tag, newest first.
     *
     * @param userId the tagged person
     * @param pageable page size carrier
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.status = 'published' AND p.deleted_at IS NULL"
                            + " AND EXISTS (SELECT 1 FROM post_user_tags t WHERE t.post_id = p.id"
                            + " AND t.tagged_user_id = :userId AND t.status = 'approved') "
                            + "ORDER BY p.published_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findFirstTaggedPosts(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset page of published posts in which the user has an approved tag, older than the cursor.
     *
     * @param userId the tagged person
     * @param cursorTime {@code published_at} of the cursor row; never null
     * @param cursorId id of the cursor row; never null
     * @param pageable page size carrier
     * @return posts ordered by the {@code (published_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p WHERE p.status = 'published' AND p.deleted_at IS NULL"
                            + " AND EXISTS (SELECT 1 FROM post_user_tags t WHERE t.post_id = p.id"
                            + " AND t.tagged_user_id = :userId AND t.status = 'approved')"
                            + " AND (p.published_at, p.id) < (:cursorTime, :cursorId) "
                            + "ORDER BY p.published_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findTaggedPostsBefore(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
