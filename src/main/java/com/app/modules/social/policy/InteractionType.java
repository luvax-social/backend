package com.app.modules.social.policy;

/** The kinds of interaction one account can direct at another, as the policy evaluates them. */
public enum InteractionType {
    COMMENT,
    MESSAGE_INITIATE,
    MESSAGE_SEND,
    MENTION,
    // Reserved: no rule handles them yet, so evaluate() refuses them until a rule set lists them.
    TAG,
    STORY_REPLY,
    STORY_RESPONSE;
}
