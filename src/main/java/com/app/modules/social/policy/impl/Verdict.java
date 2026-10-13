package com.app.modules.social.policy.impl;

import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionReason;

/**
 * What one rule says about an interaction.
 *
 * <p>{@code BASE} sets the outcome a later hide converts; only the message permission rule uses it.
 */
record Verdict(Kind kind, InteractionReason reason, InteractionOutcome base) {

    enum Kind {
        PASS,
        HIDE,
        DENY,
        BASE
    }

    private static final Verdict PASS = new Verdict(Kind.PASS, InteractionReason.NONE, null);

    static Verdict pass() {
        return PASS;
    }

    static Verdict hide(InteractionReason reason) {
        return new Verdict(Kind.HIDE, reason, null);
    }

    static Verdict deny(InteractionReason reason) {
        return new Verdict(Kind.DENY, reason, null);
    }

    static Verdict base(InteractionOutcome outcome, InteractionReason reason) {
        return new Verdict(Kind.BASE, reason, outcome);
    }
}
