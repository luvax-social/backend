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

import com.app.modules.post.entity.PostCollaborator;
import com.app.modules.post.entity.PostCollaboratorId;

@Repository
public interface PostCollaboratorRepository
        extends JpaRepository<PostCollaborator, PostCollaboratorId> {

    @Query("SELECT c FROM PostCollaborator c WHERE c.id.postId = :postId")
    List<PostCollaborator> findByPostId(@Param("postId") UUID postId);

    @Query("SELECT c FROM PostCollaborator c WHERE c.id.postId IN :postIds")
    List<PostCollaborator> findByPostIdIn(@Param("postIds") Collection<UUID> postIds);

    /**
     * Accounts that accepted a co-authorship of the post.
     *
     * @param postId the post
     * @return the co-authors' ids
     */
    @Query(
            value =
                    "SELECT user_id FROM post_collaborators"
                            + " WHERE post_id = :postId AND status = 'accepted'"
                            + " ORDER BY accepted_at, user_id",
            nativeQuery = true)
    List<UUID> findAcceptedUserIds(@Param("postId") UUID postId);

    /**
     * Published posts on which one of two accounts is an accepted co-author of the other.
     *
     * <p>Read before {@link #removeBetween} runs, so the caller knows which search documents carry
     * an author list that is about to change.
     *
     * @param ownerId one account of the pair
     * @param otherId the other account of the pair
     * @return the post ids
     */
    @Query(
            value =
                    "SELECT c.post_id FROM post_collaborators c JOIN posts p ON p.id = c.post_id"
                            + " WHERE c.status = 'accepted' AND p.status = 'published'"
                            + " AND p.deleted_at IS NULL"
                            + " AND ((p.user_id = :ownerId AND c.user_id = :otherId)"
                            + " OR (p.user_id = :otherId AND c.user_id = :ownerId))",
            nativeQuery = true)
    List<UUID> findAcceptedPostIdsBetween(
            @Param("ownerId") UUID ownerId, @Param("otherId") UUID otherId);

    /**
     * Ends every live collaboration between two accounts, whichever of them owns the post.
     *
     * <p>Run when the two block each other: an account that blocks another must not stay a
     * co-author of that account's posts, or have the other as one of its own.
     *
     * @param ownerId one account of the pair
     * @param otherId the other account of the pair
     * @return the number of collaborations ended
     */
    @Modifying
    @Query(
            value =
                    "UPDATE post_collaborators c SET status = 'removed', responded_at = now()"
                            + " FROM posts p WHERE p.id = c.post_id"
                            + " AND c.status IN ('invited', 'accepted')"
                            + " AND ((p.user_id = :ownerId AND c.user_id = :otherId)"
                            + " OR (p.user_id = :otherId AND c.user_id = :ownerId))",
            nativeQuery = true)
    int removeBetween(@Param("ownerId") UUID ownerId, @Param("otherId") UUID otherId);

    /**
     * First page of the invitations a person has not answered, on published posts, newest first.
     *
     * <p>An invitation on a draft or scheduled post is not offered yet, because the post is private
     * to its owner until it is published.
     *
     * @param userId the invited person
     * @param pageable page size carrier
     * @return pending invitations ordered by the {@code (invited_at, post_id)} tuple descending
     */
    @Query(
            value =
                    "SELECT c.* FROM post_collaborators c JOIN posts p ON p.id = c.post_id"
                            + " WHERE c.user_id = :userId AND c.status = 'invited'"
                            + " AND p.status = 'published' AND p.deleted_at IS NULL "
                            + "ORDER BY c.invited_at DESC, c.post_id DESC",
            nativeQuery = true)
    List<PostCollaborator> findFirstInvitesForUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset page of the invitations a person has not answered, older than the cursor.
     *
     * @param userId the invited person
     * @param cursorTime {@code invited_at} of the cursor row; never null
     * @param cursorId post id of the cursor row; never null
     * @param pageable page size carrier
     * @return pending invitations ordered by the {@code (invited_at, post_id)} tuple descending
     */
    @Query(
            value =
                    "SELECT c.* FROM post_collaborators c JOIN posts p ON p.id = c.post_id"
                            + " WHERE c.user_id = :userId AND c.status = 'invited'"
                            + " AND p.status = 'published' AND p.deleted_at IS NULL"
                            + " AND (c.invited_at, c.post_id) < (:cursorTime, :cursorId) "
                            + "ORDER BY c.invited_at DESC, c.post_id DESC",
            nativeQuery = true)
    List<PostCollaborator> findInvitesForUserBefore(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Invites an account again after it declined, left or was removed.
     *
     * <p>Resets the row to a fresh invitation with a new {@code invited_at}, so the invitation list
     * orders it by when it was last sent, and clears the earlier response times.
     *
     * @param postId the post
     * @param userId the invited account
     * @param invitedBy the post owner
     * @return the number of rows updated
     */
    @Modifying
    @Query(
            value =
                    "UPDATE post_collaborators SET status = 'invited', invited_by = :invitedBy,"
                            + " invited_at = now(), responded_at = NULL, accepted_at = NULL"
                            + " WHERE post_id = :postId AND user_id = :userId",
            nativeQuery = true)
    int reinvite(
            @Param("postId") UUID postId,
            @Param("userId") UUID userId,
            @Param("invitedBy") UUID invitedBy);

    default Optional<PostCollaborator> find(UUID postId, UUID userId) {
        return findById(new PostCollaboratorId(postId, userId));
    }
}
