package com.app.modules.comment.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.comment.dto.request.CreateCommentRequest;
import com.app.modules.comment.dto.request.EditCommentRequest;
import com.app.modules.comment.dto.response.CommentContextResponse;
import com.app.modules.comment.dto.response.CommentDeletionScopeResponse;
import com.app.modules.comment.dto.response.CommentResponse;

/** Comment creation, editing, soft deletion, likes, and threaded listing. */
public interface CommentService {

    /**
     * Creates a comment or reply after access, depth, moderation, slow-mode, and idempotency
     * checks, and enqueues a {@code comment.created.v1} outbox event in the same transaction.
     *
     * <p>When {@code idempotencyKey} is non-null, a retry with the same key and identical payload
     * replays the original response; the same key with a different payload is a conflict.
     * Notifications are produced asynchronously by the comment consumer, never synchronously here.
     *
     * @param actorId authenticated author
     * @param request post id, optional parent id, and content
     * @param idempotencyKey optional client-supplied idempotency key
     * @return the created comment
     */
    CommentResponse createComment(
            UUID actorId, CreateCommentRequest request, String idempotencyKey);

    /**
     * Edits the body of a comment owned by the actor and enqueues a {@code comment.edited.v1}
     * event.
     *
     * @param actorId authenticated author; must own the comment
     * @param commentId comment to edit
     * @param request new content
     * @return the updated comment
     */
    CommentResponse editComment(UUID actorId, UUID commentId, EditCommentRequest request);

    /**
     * Soft-deletes a comment and its entire subtree. Allowed for the comment owner or an admin.
     * Enqueues a {@code comment.deleted.v1} event carrying the same count it returns.
     *
     * @param actorId authenticated user; must own the comment or be an admin
     * @param commentId root of the subtree to delete
     * @return how many comments were soft-deleted, counting the target itself
     */
    CommentDeletionScopeResponse deleteComment(UUID actorId, UUID commentId);

    /**
     * Reports how many comments a deletion of this comment would soft-delete, without deleting
     * anything.
     *
     * <p>Answers the confirmation dialogue that precedes a delete. The number is an estimate: the
     * subtree can grow or shrink between this call and the delete, and no lock is taken to prevent
     * that. The delete's own return value is the authoritative figure.
     *
     * <p>Requires the same authority as the delete it precedes - the comment owner or an admin.
     * Anything the caller may not delete, including a comment hidden by a block and a comment that
     * is already soft-deleted, is reported as not found rather than forbidden, so the endpoint
     * cannot be used to probe for comments.
     *
     * @param actorId authenticated user; must own the comment or be an admin
     * @param commentId root of the subtree to measure
     * @return how many comments the deletion would cover, counting the target itself
     */
    CommentDeletionScopeResponse getDeletionScope(UUID actorId, UUID commentId);

    /**
     * Likes a comment. Liking one's own comment is permitted, matching post likes; a duplicate like
     * is a conflict. Enqueues a {@code comment.liked.v1} event.
     *
     * <p>A self-like produces no notification: {@code NotificationService.create} discards any
     * notification whose actor is also its recipient.
     *
     * @param actorId authenticated user
     * @param commentId comment to like
     */
    void likeComment(UUID actorId, UUID commentId);

    /**
     * Removes the actor's like from a comment. A no-op unlike is a conflict. Enqueues a {@code
     * comment.unliked.v1} event.
     *
     * @param actorId authenticated user
     * @param commentId comment to unlike
     */
    void unlikeComment(UUID actorId, UUID commentId);

    /**
     * Lists approved top-level comments for a post, newest first, using keyset pagination.
     *
     * <p>The first page only is preceded by up to three pinned top comments ranked by like count,
     * marked with {@code pinned} and additional to {@code limit}, and excluded from that page's
     * newest-first body so neither is returned twice. Page two onward is the pure keyset stream.
     *
     * @param viewerId authenticated viewer, or null for anonymous access
     * @param postId post whose comments are listed
     * @param sort {@code top} for the pinned block, {@code newest} for pure chronology; null or
     *     blank defaults to {@code top}, any other value is rejected
     * @param cursor opaque cursor from a previous page; null for the first page. A cursor issued
     *     under one sort mode is rejected under the other, because the two return different row
     *     sets for the same position
     * @param limit maximum number of comments to return in the newest-first body, excluding the
     *     pinned block
     * @return a cursor page of top-level comments
     */
    CursorPageResponse<CommentResponse> listTopLevelComments(
            UUID viewerId, UUID postId, String sort, String cursor, int limit);

    /**
     * Lists approved direct replies to a comment, newest first, using keyset pagination.
     *
     * @param viewerId authenticated viewer, or null for anonymous access
     * @param commentId parent comment whose direct replies are listed
     * @param cursor opaque cursor from a previous page; null for the first page
     * @param limit maximum number of replies to return
     * @return a cursor page of replies
     */
    CursorPageResponse<CommentResponse> listReplies(
            UUID viewerId, UUID commentId, String cursor, int limit);

    /**
     * Returns a comment with its ancestors, top-level first, so a notification can open a reply in
     * its thread wherever it sits.
     *
     * <p>Applies the list endpoints' rules to every comment in the chain: each must be approved,
     * not deleted, not removed by moderation, and not written by an account in a block with the
     * viewer, and the post must be visible to the viewer. Anything short of that answers {@code
     * COMMENT_NOT_FOUND}, the same as a comment that never existed, so the endpoint reveals nothing
     * the lists would not.
     *
     * @param viewerId the authenticated viewer
     * @param commentId the comment to show
     * @return the post id and the thread
     */
    CommentContextResponse getContext(UUID viewerId, UUID commentId);
}
