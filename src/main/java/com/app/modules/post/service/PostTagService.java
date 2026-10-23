package com.app.modules.post.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.request.PostTagInput;
import com.app.modules.post.dto.response.PostMediaTagResponse;
import com.app.modules.post.dto.response.TagRequestResponse;
import com.app.modules.post.entity.Post;

/**
 * People tags on the media items of a post: placing them, the tagged person's approval, and showing
 * each viewer the tags they may see.
 *
 * <p>Whether a tag is approved at once, waits for the tagged person, or waits without telling them
 * is decided by the interaction policy at write time and decided again at publication, so a setting
 * or a block that changed while the post was a draft is honoured.
 */
public interface PostTagService {

    /**
     * Replaces the tag set of a post with the given one.
     *
     * <p>A tag already present keeps its status and only moves; a new tag takes the status the
     * policy gives it; a tag left out is removed. When the post is published, each created tag is
     * announced and each removed tag recorded; otherwise nothing is announced until publication.
     * The caller has already checked that the requester owns the post.
     *
     * @param post the post, with its media rows persisted
     * @param inputs the complete tag set, each naming a media item by its position
     * @throws com.app.common.exception.AppException with {@code BAD_REQUEST} for an unknown media
     *     position or a repeated person on one item, {@code POST_TAG_LIMIT_REACHED} past the limit
     *     per item, {@code NOT_FOUND} when the person does not exist or is blocked with the owner,
     *     and {@code POST_TAG_NOT_ALLOWED} when the person's setting refuses the owner
     */
    void replaceTags(Post post, List<PostTagInput> inputs);

    /**
     * Re-decides every tag of a post that is about to be published and announces the survivors.
     *
     * <p>A tag the policy now denies is deleted without a word. A tag whose outcome changed takes
     * the new status. Each remaining tag enqueues {@code post.tag.created.v1}; a tag routed to the
     * hidden requests carries {@code notify=false}.
     *
     * @param post the post being published for the first time
     */
    void announceOnPublication(Post post);

    /**
     * Approves a pending tag of the viewer.
     *
     * @param viewerId the tagged person
     * @param postId the post
     * @param tagId the tag
     * @throws com.app.common.exception.AppException with {@code POST_TAG_NOT_FOUND} when the tag
     *     does not exist, is not the viewer's, or sits on a post the viewer cannot see
     */
    void approve(UUID viewerId, UUID postId, UUID tagId);

    /**
     * Removes a tag, at any time, as the post owner or as the tagged person.
     *
     * @param requesterId the owner or the tagged person
     * @param postId the post
     * @param tagId the tag
     * @throws com.app.common.exception.AppException with {@code POST_TAG_NOT_FOUND} otherwise
     */
    void remove(UUID requesterId, UUID postId, UUID tagId);

    /**
     * Lists the tags of the viewer that wait for approval, newest first.
     *
     * @param viewerId the tagged person
     * @param cursor opaque cursor from the previous page, or null
     * @param size requested page size
     * @return a cursor page of tag requests on published posts
     */
    CursorPageResponse<TagRequestResponse> listPending(UUID viewerId, String cursor, int size);

    /**
     * Loads the tags to show a viewer, grouped by post and media item.
     *
     * <p>An approved tag is shown to everyone who can see the post. A pending tag is shown only to
     * the post owner and the tagged person. A tag of an account that is blocked with the viewer, or
     * that no longer exists, is left out.
     *
     * @param viewerId the viewer
     * @param posts the posts being rendered
     * @return tags keyed by {@code post_media} id; absent when an item has none visible
     */
    Map<UUID, List<PostMediaTagResponse>> loadForPosts(UUID viewerId, Collection<Post> posts);
}
