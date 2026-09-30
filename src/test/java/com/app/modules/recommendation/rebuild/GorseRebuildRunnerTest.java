package com.app.modules.recommendation.rebuild;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GorseRebuildRunnerTest {

    @Mock private GorseRebuildService service;
    @Mock private GorseRebuildRunRepository runs;

    private GorseRebuildRunner runner;

    @BeforeEach
    void setUp() {
        runner = new GorseRebuildRunner(service, runs);
    }

    private GorseRebuildRun run(
            String token, GorseRebuildStatus status, GorseRebuildPhase phase, UUID checkpoint) {
        GorseRebuildRun run = new GorseRebuildRun();
        run.setId(UUID.randomUUID());
        run.setToken(token);
        run.setStatus(status);
        run.setPhase(phase);
        run.setCheckpointId(checkpoint);
        return run;
    }

    @Test
    void runOnce_noToken_doesNothing() {
        ReflectionTestUtils.setField(runner, "token", "");

        runner.runOnce();

        verifyNoInteractions(service, runs);
    }

    @Test
    void runOnce_blankToken_doesNothing() {
        ReflectionTestUtils.setField(runner, "token", "   ");

        runner.runOnce();

        verifyNoInteractions(service, runs);
    }

    @Test
    void runOnce_unusedToken_createsARunAtTheFirstPhaseAndExecutesIt() {
        ReflectionTestUtils.setField(runner, "token", "2026-09-30-1");
        GorseRebuildRun created =
                run("2026-09-30-1", GorseRebuildStatus.RUNNING, GorseRebuildPhase.PREFLIGHT, null);
        when(runs.findByToken("2026-09-30-1")).thenReturn(Optional.empty());
        when(runs.create("2026-09-30-1")).thenReturn(created);

        runner.runOnce();

        verify(service).execute(created);
    }

    @Test
    void runOnce_runningRun_resumesFromItsPhaseWithoutCreatingAnother() {
        ReflectionTestUtils.setField(runner, "token", "crashed");
        GorseRebuildRun interrupted =
                run(
                        "crashed",
                        GorseRebuildStatus.RUNNING,
                        GorseRebuildPhase.FEEDBACK,
                        UUID.randomUUID());
        when(runs.findByToken("crashed")).thenReturn(Optional.of(interrupted));

        runner.runOnce();

        verify(runs, never()).create(any());
        verify(service).execute(interrupted);
    }

    @Test
    void runOnce_failedRun_resumesSoTheOperatorCanRestartAfterFixingTheCause() {
        ReflectionTestUtils.setField(runner, "token", "blocked");
        GorseRebuildRun failed =
                run("blocked", GorseRebuildStatus.FAILED, GorseRebuildPhase.PREFLIGHT, null);
        when(runs.findByToken("blocked")).thenReturn(Optional.of(failed));

        runner.runOnce();

        verify(service).execute(failed);
    }

    @Test
    void runOnce_doneRun_doesNothingSoALeftOnToggleRepeatsNothing() {
        ReflectionTestUtils.setField(runner, "token", "finished");
        when(runs.findByToken("finished"))
                .thenReturn(
                        Optional.of(
                                run(
                                        "finished",
                                        GorseRebuildStatus.DONE,
                                        GorseRebuildPhase.DONE,
                                        null)));

        runner.runOnce();

        verify(service, never()).execute(any());
        verify(runs, never()).create(any());
    }

    @Test
    void runOnce_failedVerificationRun_doesNothingUntilTheOperatorChoosesANewToken() {
        ReflectionTestUtils.setField(runner, "token", "gap");
        when(runs.findByToken("gap"))
                .thenReturn(
                        Optional.of(
                                run(
                                        "gap",
                                        GorseRebuildStatus.FAILED_VERIFICATION,
                                        GorseRebuildPhase.VERIFY,
                                        null)));

        runner.runOnce();

        verify(service, never()).execute(any());
    }

    @Test
    void runOnce_repositoryFails_doesNotPropagateOutOfTheRebuildThread() {
        ReflectionTestUtils.setField(runner, "token", "broken");
        when(runs.findByToken("broken")).thenThrow(new IllegalStateException("database down"));

        runner.runOnce();

        verifyNoInteractions(service);
    }
}
