package com.app.common.turnstile;

import java.util.Locale;

/**
 * What one Cloudflare Turnstile verification attempt actually established.
 *
 * <p>Three values rather than a boolean, because "Cloudflare says this token is not valid" and
 * "Cloudflare did not answer" are different facts and different callers answer them differently.
 * Collapsing them into a boolean forces the verifier to pick a policy, which is the caller's
 * decision: the auth surfaces fail open on {@link #UNAVAILABLE} because a per-caller rate limit
 * already guards them, while the public support form fails closed because Turnstile is its only
 * control.
 */
public enum TurnstileOutcome {

    /** Cloudflare answered {@code success: true}. */
    VERIFIED,

    /** Cloudflare answered {@code success: false}, or the client submitted a blank token. */
    REJECTED,

    /**
     * No usable answer: timeout, connection failure, non-2xx status, malformed body, or a secret
     * that was never configured. Never reachable from client input alone.
     */
    UNAVAILABLE;

    /** Lower-case form used as the {@code outcome} metric tag value. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
