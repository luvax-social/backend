package com.app.modules.message.messaging;

/** Versioned message domain event types published through the transactional outbox. */
public final class MessageEventTypes {

    public static final String MESSAGE_SENT_V1 = "message.sent.v1";
    public static final String MESSAGE_DELETED_V1 = "message.deleted.v1";
    public static final String POST_SHARED_V1 = "post.shared.v1";
    public static final String MESSAGE_REQUEST_RESOLVED_V1 = "message.request.resolved.v1";

    private MessageEventTypes() {}
}
