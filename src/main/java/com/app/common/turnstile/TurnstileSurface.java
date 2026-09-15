package com.app.common.turnstile;

/**
 * The submission surface a Turnstile verification was performed for.
 *
 * <p>Carried only so an outage or an attack shows up per-surface on the {@code
 * turnstile.verification.total} counter. An enum rather than a free string keeps the tag
 * cardinality bounded, which a metrics backend cares about.
 */
public enum TurnstileSurface {
    LOGIN("login"),
    REGISTER("register"),
    FORGOT_PASSWORD("forgot_password"),
    RESET_PASSWORD("reset_password"),
    RESEND_VERIFICATION("resend_verification"),
    REPORT("report"),
    PUBLIC_SUPPORT("public_support");

    private final String tag;

    TurnstileSurface(String tag) {
        this.tag = tag;
    }

    /** Value used for the {@code surface} metric tag. */
    public String tag() {
        return tag;
    }
}
