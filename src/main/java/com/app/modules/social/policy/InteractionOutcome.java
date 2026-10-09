package com.app.modules.social.policy;

/** What the caller does with an interaction the policy has evaluated. */
public enum InteractionOutcome {
    ALLOW,
    ALLOW_HIDDEN, // Stored, but shown only to its author and the target, and never notified
    ROUTE_TO_REQUESTS, // Delivered into the recipient's message requests folder
    ROUTE_TO_HIDDEN_REQUESTS, // Delivered into the recipient's hidden requests folder
    DENY
}
