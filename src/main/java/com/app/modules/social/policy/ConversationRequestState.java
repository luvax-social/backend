package com.app.modules.social.policy;

/**
 * Where a direct conversation stands in the message request flow, as the policy sees it.
 *
 * <p>Declared here rather than in the message module so the policy never depends on {@code
 * message}, which already depends on {@code social}.
 */
public enum ConversationRequestState {
    ACCEPTED,
    PENDING,
    PENDING_HIDDEN, // Pending, and filed in the recipient's hidden requests folder
    DECLINED
}
