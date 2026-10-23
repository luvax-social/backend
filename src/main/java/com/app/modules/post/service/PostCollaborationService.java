package com.app.modules.post.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.CollaborationInviteResponse;
import com.app.modules.post.dto.response.PostCollaboratorResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.CollaborationStatus;

/**
 * Co-authoring a post: the owner's invitations, the invitee's answer, and what each viewer sees.
 *
 * <p>The owner keeps sole edit and delete rights. An invited account is shown on the post only
 * after accepting. Invitations are stored with the post and announced when it is first published,
 * and can be answered only while it is published.
 */
public interface PostCollaborationService {

    /**
     * What a viewer sees of the collaboration on one post.
     *
     * @param collaborators the accepted co-authors, plus the invitations the viewer may see
     * @param viewerStatus the viewer's own invitation or co-authorship, or null
     */
    record CollaborationView(
            List<PostCollaboratorResponse> collaborators, CollaborationStatus viewerStatus) {}

    /**
     * Sets the accounts invited to co-author a post.
     *
     * <p>The list is the complete set that should hold a live invitation or co-authorship. An
     * account left out is withdrawn or removed; an account that declined, left or was removed and
     * is named again is invited anew. On a published post each new invitation is announced at once;
     * otherwise nothing is announced until publication. The caller has already checked that the
     * requester owns the post.
     *
     * @param post the post
     * @param userIds the complete set of invitees
     * @throws com.app.common.exception.AppException with {@code POST_COLLABORATOR_LIMIT_REACHED}
     *     past the limit, {@code BAD_REQUEST} for the owner or a repeated account, and {@code
     *     NOT_FOUND} for an unknown account or one blocked with the owner
     */
    void replaceInvites(Post post, List<UUID> userIds);

    /**
     * Re-decides each invitation of a post about to be published and announces the survivors.
     *
     * <p>An invitation now refused by a block is withdrawn silently. Each remaining one enqueues
     * {@code post.collaboration.invited.v1}.
     *
     * @param post the post being published for the first time
     */
    void announceOnPublication(Post post);

    /**
     * Accepts an invitation, making the viewer a co-author.
     *
     * @param viewerId the invited account
     * @param postId the post
     * @throws com.app.common.exception.AppException with {@code POST_COLLABORATION_NOT_FOUND} when
     *     there is no open invitation for the viewer on a published post
     */
    void accept(UUID viewerId, UUID postId);

    /**
     * Declines an invitation.
     *
     * @param viewerId the invited account
     * @param postId the post
     * @throws com.app.common.exception.AppException with {@code POST_COLLABORATION_NOT_FOUND} when
     *     there is no open invitation
     */
    void decline(UUID viewerId, UUID postId);

    /**
     * Leaves a post the viewer co-authors. The earlier acceptance time is kept.
     *
     * @param viewerId the co-author
     * @param postId the post
     * @throws com.app.common.exception.AppException with {@code POST_COLLABORATION_NOT_FOUND} when
     *     the viewer is not a co-author of the post
     */
    void leave(UUID viewerId, UUID postId);

    /**
     * Lists the invitations the viewer has not answered, newest first.
     *
     * @param viewerId the invited account
     * @param cursor opaque cursor from the previous page, or null
     * @param size requested page size
     * @return a cursor page of invitations on published posts
     */
    CursorPageResponse<CollaborationInviteResponse> listInvites(
            UUID viewerId, String cursor, int size);

    /**
     * Loads the collaboration a viewer sees on many posts at once.
     *
     * <p>An accepted co-author is shown to everyone who can see the post. An invitation is shown
     * only to the post owner and the invitee. A declined, left or removed account is never shown.
     *
     * @param viewerId the viewer
     * @param posts the posts being rendered
     * @return the view keyed by post id; absent when a post has no collaboration
     */
    Map<UUID, CollaborationView> loadForPosts(UUID viewerId, Collection<Post> posts);
}
