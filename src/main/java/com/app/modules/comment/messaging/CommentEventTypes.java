package com.app.modules.comment.messaging;

/** Versioned comment domain event types published through the transactional outbox. */
public final class CommentEventTypes {

    public static final String COMMENT_CREATED_V1 = "comment.created.v1";
    public static final String COMMENT_EDITED_V1 = "comment.edited.v1";
    public static final String COMMENT_DELETED_V1 = "comment.deleted.v1";
    public static final String COMMENT_LIKED_V1 = "comment.liked.v1";
    public static final String COMMENT_UNLIKED_V1 = "comment.unliked.v1";
    public static final String COMMENT_UNHIDDEN_V1 = "comment.unhidden.v1";

    private CommentEventTypes() {}
}
