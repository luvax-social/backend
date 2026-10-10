package com.app.modules.comment.enums;

import com.app.modules.social.policy.InteractionReason;

/**
 * Why a comment was persisted hidden instead of approved.
 *
 * <p>The lowercase {@link #value()} is what {@code comments.hidden_reason} stores, and the column
 * is set exactly when {@code moderation_status} is {@code hidden}.
 */
public enum CommentHiddenReason {
    HIDDEN_WORDS("hidden_words"),
    LIMITED_INTERACTION("limited_interaction");

    private final String value;

    CommentHiddenReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * Maps the reason the interaction policy gave for hiding a comment.
     *
     * @param reason the policy's reason for an {@code ALLOW_HIDDEN} decision
     * @return the stored hidden reason
     * @throws IllegalStateException when the policy hid a comment for a reason no comment can carry
     */
    public static CommentHiddenReason from(InteractionReason reason) {
        return switch (reason) {
            case HIDDEN_WORD -> HIDDEN_WORDS;
            case LIMITED_NON_FOLLOWER, LIMITED_RECENT_FOLLOWER -> LIMITED_INTERACTION;
            default ->
                    throw new IllegalStateException(
                            "A comment cannot be hidden for policy reason " + reason);
        };
    }
}
