package com.app.modules.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.post.entity.PostNotInterested;
import com.app.modules.post.entity.PostNotInterestedId;

@Repository
public interface PostNotInterestedRepository
        extends JpaRepository<PostNotInterested, PostNotInterestedId> {

    /**
     * Records a not-interested mark unless the pair already has one.
     *
     * <p>One statement rather than exists-then-save, so two concurrent requests for the same pair
     * cannot collide on the primary key and exactly one of them reports an insert. {@code
     * created_at} comes from its column default.
     *
     * @param userId the viewer marking the post
     * @param postId the post being marked
     * @return 1 when a row was inserted, 0 when the pair was already marked
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "INSERT INTO post_not_interested (user_id, post_id)"
                            + " VALUES (:userId, :postId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("postId") UUID postId);

    /**
     * The posts, among {@code postIds}, that the viewer marked not interested.
     *
     * <p>One probe per candidate on the {@code (user_id, post_id)} primary key, so a feed round
     * pays a single indexed query however many candidates it holds.
     *
     * @param userId the viewer whose marks are read
     * @param postIds candidate posts a feed is about to show
     * @return the marked post ids; empty when none is marked
     */
    @Query(
            value =
                    "SELECT post_id FROM post_not_interested"
                            + " WHERE user_id = :userId AND post_id IN (:postIds)",
            nativeQuery = true)
    List<UUID> findMarkedPostIds(
            @Param("userId") UUID userId, @Param("postIds") Collection<UUID> postIds);

    /**
     * Removes a not-interested mark, returning the affected-row count so the caller can tell a real
     * undo from a no-op without loading the row first.
     *
     * @param userId the viewer who marked the post
     * @param postId the post that was marked
     * @return number of rows deleted (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "DELETE FROM PostNotInterested n"
                    + " WHERE n.id.userId = :userId AND n.id.postId = :postId")
    int deleteByUserAndPost(@Param("userId") UUID userId, @Param("postId") UUID postId);
}
