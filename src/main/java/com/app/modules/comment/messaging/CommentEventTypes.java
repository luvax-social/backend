package com.app.modules.comment.messaging;

/** Versioned comment domain event types published through the transactional outbox. */
public final class CommentEventTypes {

    public static final String COMMENT_CREATED_V1 = "comment.created.v1";
    public static final String COMMENT_EDITED_V1 = "comment.edited.v1";
    public static final String COMMENT_DELETED_V1 = "comment.deleted.v1";
    public static final String COMMENT_LIKED_V1 = "comment.liked.v1";
    public static final String COMMENT_UNLIKED_V1 = "comment.unliked.v1";
    public static final String COMMENT_UNHIDDEN_V1 = "comment.unhidden.v1";

    /**
     * A comment was persisted hidden by the interaction rules, at creation or by an edit.
     *
     * <p>An internal analytics signal and nothing else: the author is never told a comment was
     * hidden, so no live or notification consumer may act on it. The payload carries the post, the
     * comment and the reason only.
     */
    public static final String COMMENT_HIDDEN_V1 = "comment.hidden.v1";

    private CommentEventTypes() {}
}
