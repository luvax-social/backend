package com.app.modules.social.policy;

/**
 * Why the policy reached its outcome.
 *
 * <p>For internal use and logging only: a caller never shows the actor a reason that would reveal a
 * block, a limit or a hidden-word match.
 */
public enum InteractionReason {
    NONE,
    SELF,
    BLOCKED,
    LIMITED_NON_FOLLOWER,
    LIMITED_RECENT_FOLLOWER,
    PERMISSION_NOBODY,
    PERMISSION_FOLLOWING_ONLY,
    NOT_FOLLOWED_REQUEST,
    HIDDEN_WORD,
    AWAITING_ACCEPTANCE,
    // Reserved: a declined request answers AWAITING_ACCEPTANCE so the requester cannot tell
    REQUEST_DECLINED
}
