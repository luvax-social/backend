package com.app.modules.recommendation.rebuild;

/**
 * Where a rebuild run stands.
 *
 * <p>{@code RUNNING} and {@code FAILED} are resumable with the same token; {@code DONE} and {@code
 * FAILED_VERIFICATION} are final, and a new rebuild needs a new token.
 */
public enum GorseRebuildStatus {
    RUNNING,
    FAILED,
    FAILED_VERIFICATION,
    DONE;

    public boolean isFinal() {
        return this == DONE || this == FAILED_VERIFICATION;
    }
}
