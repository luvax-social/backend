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

import com.app.modules.post.entity.PostUserTag;

@Repository
public interface PostUserTagRepository extends JpaRepository<PostUserTag, UUID> {

    List<PostUserTag> findByPostId(UUID postId);

    List<PostUserTag> findByPostIdIn(Collection<UUID> postIds);

    Optional<PostUserTag> findByIdAndPostId(UUID id, UUID postId);

    long countByPostMediaId(UUID postMediaId);

    @Modifying
    @Query("DELETE FROM PostUserTag t WHERE t.postId = :postId")
    int deleteByPostId(@Param("postId") UUID postId);

    /**
     * Removes every tag between a tagger and a tagged account, in both directions of ownership.
     *
     * <p>Run when two accounts block each other: a tag between them must not survive the block.
     *
     * @param ownerId one account of the pair
     * @param otherId the other account of the pair
     * @return the number of tags removed
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM post_user_tags t USING posts p WHERE p.id = t.post_id AND"
                            + " ((p.user_id = :ownerId AND t.tagged_user_id = :otherId)"
                            + " OR (p.user_id = :otherId AND t.tagged_user_id = :ownerId))",
            nativeQuery = true)
    int deleteBetween(@Param("ownerId") UUID ownerId, @Param("otherId") UUID otherId);

    /**
     * First page of pending tags of a person on published posts, newest first.
     *
     * <p>A tag on a draft or scheduled post is not offered yet, because the post is private to its
     * owner until it is published.
     *
     * @param userId the tagged person
     * @param pageable page size carrier
     * @return pending tags ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM post_user_tags t JOIN posts p ON p.id = t.post_id"
                            + " WHERE t.tagged_user_id = :userId AND t.status = 'pending'"
                            + " AND p.status = 'published' AND p.deleted_at IS NULL "
                            + "ORDER BY t.created_at DESC, t.id DESC",
            nativeQuery = true)
    List<PostUserTag> findFirstPendingForUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset page of pending tags of a person on published posts, older than the cursor.
     *
     * @param userId the tagged person
     * @param cursorTime {@code created_at} of the cursor row; never null
     * @param cursorId id of the cursor row; never null
     * @param pageable page size carrier
     * @return pending tags ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT t.* FROM post_user_tags t JOIN posts p ON p.id = t.post_id"
                            + " WHERE t.tagged_user_id = :userId AND t.status = 'pending'"
                            + " AND p.status = 'published' AND p.deleted_at IS NULL"
                            + " AND (t.created_at, t.id) < (:cursorTime, :cursorId) "
                            + "ORDER BY t.created_at DESC, t.id DESC",
            nativeQuery = true)
    List<PostUserTag> findPendingForUserBefore(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
