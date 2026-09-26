package com.app.modules.hashtag.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.hashtag.entity.Hashtag;

@Repository
public interface HashtagRepository extends JpaRepository<Hashtag, UUID>, HashtagRepositoryCustom {

    Optional<Hashtag> findByNameIgnoreCase(String name);

    Optional<Hashtag> findByName(String name);

    /**
     * Inserts a hashtag if absent; relies on the unique constraint on name. No-op when the name
     * already exists.
     *
     * <p>{@code DO NOTHING} rather than {@code DO UPDATE} is load-bearing for the lifecycle: an
     * existing row keeps its {@code status}, so ordinary first-use traffic can never resurrect a
     * banned or deleted tag by writing over the administrator's decision.
     *
     * @param name the normalized hashtag name to insert
     */
    @Modifying
    @Query(
            value = "INSERT INTO hashtags (name) VALUES (:name) ON CONFLICT (name) DO NOTHING",
            nativeQuery = true)
    void upsertByName(@Param("name") String name);

    /**
     * Fuzzy hashtag search using the pg_trgm similarity operator, ordered by popularity then name.
     *
     * <p>Narrowed to active hashtags: a banned or deleted tag is absent from every hashtag surface,
     * and this is the PostgreSQL fallback behind the public search, which doubles as autocomplete.
     *
     * @param query the search term matched against {@code name} via the {@code %} trigram operator
     * @param limit maximum number of rows to return
     * @param offset number of leading rows to skip for pagination
     * @return matching active hashtags ordered by {@code post_count} descending, then {@code name}
     *     ascending
     */
    @Query(
            value =
                    "SELECT * FROM hashtags WHERE name % :query AND status = 'active'"
                            + " ORDER BY post_count DESC, name ASC LIMIT :limit OFFSET :offset",
            nativeQuery = true)
    List<Hashtag> searchByNameTrgm(
            @Param("query") String query, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Returns the most used active hashtags, most used first.
     *
     * <p>Served by {@code idx_hashtags_active_post_count} as an index scan with a limit, which is
     * why the administrative statistics surface computes this one metric live instead of reading a
     * snapshot: it is the metric where staleness is most visible and the only one cheap enough to
     * answer on a request path.
     *
     * @param limit maximum number of rows to return
     * @return active hashtags ordered by {@code post_count} descending, then {@code name} ascending
     */
    @Query(
            value =
                    "SELECT * FROM hashtags WHERE status = 'active'"
                            + " ORDER BY post_count DESC, name ASC LIMIT :limit",
            nativeQuery = true)
    List<Hashtag> findTopActiveByPostCount(@Param("limit") int limit);

    /**
     * Returns the subset of the supplied names that name a banned hashtag.
     *
     * <p>One statement for a whole caption. Served by the unique index on {@code name} as an index
     * scan issuing one search per probed name; a status-leading index would have to walk every
     * banned row instead, and the banned set grows without bound while a caption supplies at most a
     * few dozen names.
     *
     * <p>Native rather than JPQL so the status literal is the PostgreSQL enum value the benchmarked
     * statement used, with no attribute-converter round trip in between. Callers must not pass an
     * empty collection: {@code IN ()} is a syntax error.
     *
     * @param names normalized hashtag names to test; never empty
     * @return the names among them whose hashtag row is banned; empty when none is
     */
    @Query(
            value =
                    "SELECT h.name FROM hashtags h WHERE h.status = 'banned' AND h.name IN (:names)",
            nativeQuery = true)
    List<String> findBannedNames(@Param("names") Collection<String> names);

    /**
     * Returns the hashtag names associated with the given post, ordered by name.
     *
     * <p>Read from the source-of-truth rows rather than carried in an event payload, because a
     * hashtag name is user-authored text and a rename would leave any name already queued stale.
     *
     * @param postId the post whose hashtag names are returned
     * @return hashtag names for the post; empty when it carries none
     */
    @Query(
            value =
                    "SELECT h.name FROM hashtags h JOIN post_hashtags ph ON ph.hashtag_id = h.id"
                            + " WHERE ph.post_id = :postId ORDER BY h.name",
            nativeQuery = true)
    List<String> findNamesByPostId(@Param("postId") UUID postId);

    /**
     * Returns scalar index projections for the given hashtag ids.
     *
     * <p>Selects columns directly rather than loading managed entities so the trigger-updated
     * {@code post_count} is read from the current database state within the transaction and is not
     * served from the Hibernate L1 cache.
     *
     * @param ids the hashtag ids to project
     * @return index projections for the matching hashtags
     */
    @Query(
            "SELECT h.id AS id, h.name AS name, h.postCount AS postCount, h.status AS status,"
                    + " h.createdAt AS createdAt FROM Hashtag h WHERE h.id IN :ids")
    List<HashtagIndexProjection> findIndexProjectionsByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * How many currently listable posts carry this hashtag.
     *
     * <p>Deliberately not {@code hashtags.post_count}. That column is trigger-maintained from
     * {@code post_hashtags} alone, and removing or soft-deleting a post does not remove its
     * association rows, so it counts associations ever made rather than posts anybody can open. It
     * is the right number for the administrative registry and for search ranking, and the wrong one
     * to print above a grid that lists only published, non-deleted posts.
     *
     * <p>The predicate is character-for-character the one {@code
     * PostRepository.findPublishedByHashtagId} lists by, so the header and the grid answer the same
     * question. It is not viewer-scoped: a post hidden from one reader by the visibility chain
     * still counts here, so a private or blocked author can still leave the header one ahead of
     * what that particular reader sees. Making it exact per viewer would mean running the whole
     * visibility chain as a count on every page load, which costs far more than the discrepancy.
     *
     * <p>Served by {@code idx_post_hashtags_tag (hashtag_id, post_id)} on the join side.
     *
     * @param hashtagId the hashtag whose posts are counted
     * @return number of published, non-deleted posts carrying the hashtag
     */
    @Query(
            value =
                    "SELECT count(*) FROM post_hashtags ph JOIN posts p ON p.id = ph.post_id"
                            + " WHERE ph.hashtag_id = :hashtagId AND p.status = 'published'"
                            + " AND p.deleted_at IS NULL",
            nativeQuery = true)
    int countListablePosts(@Param("hashtagId") UUID hashtagId);
}
