package com.app.modules.post.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
                            + " status::text AS status, status_before_moderation::text AS"
                            + " statusBeforeModeration FROM posts WHERE id = :postId",
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
     * @param postId post identifier
     * @param status lowercase PostgreSQL post status to restore to
     * @return number of updated posts
     */
    @Modifying
    @Query(
            value =
                    "UPDATE posts SET status = CAST(:status AS post_status),"
                            + " status_before_moderation = NULL, deleted_at = NULL WHERE id = :postId",
            nativeQuery = true)
    int applyModerationRestore(@Param("postId") UUID postId, @Param("status") String status);

    /**
     * First keyset page of a user's published posts, newest first.
     *
     * <p>Paired with {@link #findUserPostsBefore}; the no-cursor variant avoids binding an untyped
     * null cursor. Native so the sibling can use a row-value tuple comparison for an exact index
     * seek, which JPQL cannot express.
     *
     * @param userId post author
     * @param pageable page size carrier (page number is always 0 for keyset paging)
     * @return posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE user_id = :userId AND status = 'published'"
                            + " AND deleted_at IS NULL "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Post> findFirstUserPosts(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset page of a user's published posts strictly after the cursor tuple, newest first.
     *
     * <p>The {@code (created_at, id)} row-value comparison seeks directly to the cursor position
     * and never drops rows that share a boundary {@code created_at}. Served exactly by {@code
     * idx_posts_user_created_id} (V34).
     *
     * @param userId post author
     * @param cursorTime {@code created_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code created_at}; never null
     * @param pageable page size carrier
     * @return posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE user_id = :userId AND status = 'published'"
                            + " AND deleted_at IS NULL "
                            + "AND (created_at, id) < (:cursorTime, :cursorId) "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Post> findUserPostsBefore(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * First keyset page of a user's published posts restricted to the given types, newest first.
     *
     * <p>Separate from {@link #findFirstUserPosts} rather than binding an empty list, because an
     * empty {@code IN} list is not valid SQL. The types arrive as one comma-delimited parameter
     * that Postgres expands into a {@code post_type[]}, so the cast lands on the parameter and the
     * comparison stays enum-to-enum. Casting the column to text instead discards its statistics and
     * was measured to replace the index scan with a bitmap heap scan plus a sort.
     *
     * @param userId post author
     * @param types comma-delimited {@code post_type} names; never empty
     * @param pageable page size carrier (page number is always 0 for keyset paging)
     * @return posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE user_id = :userId AND status = 'published'"
                            + " AND deleted_at IS NULL "
                            + "AND post_type = ANY(CAST(string_to_array(:types, ',') AS"
                            + " post_type[])) "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Post> findFirstUserPostsByType(
            @Param("userId") UUID userId, @Param("types") String types, Pageable pageable);

    /**
     * Keyset page of a user's published posts of the given types, strictly after the cursor tuple.
     *
     * <p>Same predicate as {@link #findFirstUserPostsByType} with the row-value cursor comparison
     * added. Served by {@code idx_posts_user_created_id} (V34) with the type predicate applied as a
     * filter; measured across the first, a deep, and a low-selectivity page without degradation, so
     * no type-aware index is required.
     *
     * @param userId post author
     * @param types comma-delimited {@code post_type} names; never empty
     * @param cursorTime {@code created_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code created_at}; never null
     * @param pageable page size carrier
     * @return posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE user_id = :userId AND status = 'published'"
                            + " AND deleted_at IS NULL "
                            + "AND post_type = ANY(CAST(string_to_array(:types, ',') AS"
                            + " post_type[])) "
                            + "AND (created_at, id) < (:cursorTime, :cursorId) "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Post> findUserPostsByTypeBefore(
            @Param("userId") UUID userId,
            @Param("types") String types,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * First keyset batch of posts in the given status across all users, newest first.
     *
     * <p>Used by the Elasticsearch index seed runner to start walking the table.
     *
     * @param status status filter; the seed runner indexes published posts only
     * @param pageable batch size carrier
     * @return posts ordered by {@code created_at} descending
     */
    @Query("SELECT p FROM Post p WHERE p.status = :status ORDER BY p.createdAt DESC")
    List<Post> findFirstPublished(@Param("status") PostStatus status, Pageable pageable);

    /**
     * Keyset batch of posts in the given status older than the cursor, newest first.
     *
     * @param status status filter; the seed runner indexes published posts only
     * @param cursor exclusive upper bound on {@code created_at}; never null
     * @param pageable batch size carrier
     * @return posts ordered by {@code created_at} descending
     */
    @Query(
            "SELECT p FROM Post p WHERE p.status = :status "
                    + "AND p.createdAt < :cursor "
                    + "ORDER BY p.createdAt DESC")
    List<Post> findPublishedBefore(
            @Param("status") PostStatus status,
            @Param("cursor") OffsetDateTime cursor,
            Pageable pageable);

    /**
     * First keyset page of published posts authored by any user in {@code authorIds}, newest first.
     *
     * <p>Paired with {@link #findFeedPostsBefore}; the no-cursor variant avoids binding an untyped
     * null timestamp, which PostgreSQL cannot type-infer.
     *
     * @param authorIds post authors eligible to appear in the feed; must not be empty
     * @param pageable page size carrier (page number is always 0 for keyset paging)
     * @return posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE user_id IN (:authorIds) AND status = 'published'"
                            + " AND deleted_at IS NULL "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Post> findFirstFeedPosts(@Param("authorIds") List<UUID> authorIds, Pageable pageable);

    /**
     * Keyset page of published posts authored by any user in {@code authorIds}, older than the
     * cursor, newest first.
     *
     * @param authorIds post authors eligible to appear in the feed; must not be empty
     * @param cursorTime {@code created_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code created_at}; never null
     * @param pageable page size carrier
     * @return posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM posts WHERE user_id IN (:authorIds) AND status = 'published'"
                            + " AND deleted_at IS NULL "
                            + "AND (created_at, id) < (:cursorTime, :cursorId) "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Post> findFeedPostsBefore(
            @Param("authorIds") List<UUID> authorIds,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

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
     * (created_at, id)} ordering matches every other recent-posts read so a shared tiebreaker keeps
     * rows that share a {@code created_at} from being dropped between pages.
     *
     * @param hashtagId hashtag whose posts are listed
     * @param pageable offset and limit carrier; supply {@link
     *     com.app.common.pagination.OffsetPageable} so the exact cursor offset is preserved
     * @return published, non-deleted posts ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT p.* FROM posts p "
                            + "JOIN post_hashtags ph ON ph.post_id = p.id "
                            + "WHERE ph.hashtag_id = :hashtagId AND p.status = 'published' "
                            + "AND p.deleted_at IS NULL "
                            + "ORDER BY p.created_at DESC, p.id DESC",
            nativeQuery = true)
    List<Post> findPublishedByHashtagId(@Param("hashtagId") UUID hashtagId, Pageable pageable);
}
