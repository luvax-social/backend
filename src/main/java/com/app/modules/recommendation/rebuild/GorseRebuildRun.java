package com.app.modules.recommendation.rebuild;

import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * One row of {@code gorse_rebuild_runs}: the durable record of an operator-triggered rebuild.
 *
 * <p>Mutable on purpose. The service advances it batch by batch and saves it after each one, so a
 * restart with the same token resumes after {@code checkpointId} in {@code phase}.
 */
@Getter
@Setter
public class GorseRebuildRun {

    private UUID id;
    private String token;
    private GorseRebuildStatus status;
    private GorseRebuildPhase phase;
    private UUID checkpointId;
    private long usersSent;
    private long itemsSent;
    private long feedbackSent;
    private long feedbackSkipped;
    private String lastError;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    /** Moves to the next phase, clearing the checkpoint because it belongs to one phase only. */
    public void enter(GorseRebuildPhase next) {
        this.phase = next;
        this.checkpointId = null;
    }
}
