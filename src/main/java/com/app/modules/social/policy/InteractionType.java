package com.app.modules.social.policy;

/** The kinds of interaction one account can direct at another, as the policy evaluates them. */
public enum InteractionType {
    COMMENT,
    MESSAGE_INITIATE,
    MESSAGE_SEND,
    MENTION,
    // Tagging a person in a post: the block, limit and tag permission rules decide it. A tag is
    // approved at once, waits for the tagged person's approval, or waits without telling them.
    TAG,
    // Inviting an account to co-author a post: only a block can refuse it, because the invitation
    // itself is a question the invitee answers.
    COLLABORATION_INVITE,
    // Reserved: no rule handles them yet, so evaluate() refuses them until a rule set lists them.
    STORY_REPLY,
    STORY_RESPONSE;
}
