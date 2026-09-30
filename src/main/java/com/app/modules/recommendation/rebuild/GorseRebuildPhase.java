package com.app.modules.recommendation.rebuild;

/**
 * The stages of one Gorse rebuild, in the order they run.
 *
 * <p>The stored name is what the {@code gorse_rebuild_runs.phase} check constraint allows, and the
 * gauge value is what the {@code luvax_gorse_rebuild_phase} meter reports while the phase is
 * current: 1 to 6 for the working phases and 7 for done, with 0 meaning idle and -1 failed.
 */
public enum GorseRebuildPhase {
    PREFLIGHT(1),
    PURGE(2),
    USERS(3),
    ITEMS(4),
    FEEDBACK(5),
    VERIFY(6),
    DONE(7);

    private final int gaugeValue;

    GorseRebuildPhase(int gaugeValue) {
        this.gaugeValue = gaugeValue;
    }

    public int gaugeValue() {
        return gaugeValue;
    }

    /** True when this phase comes strictly after {@code other}. */
    public boolean isAfter(GorseRebuildPhase other) {
        return ordinal() > other.ordinal();
    }
}
