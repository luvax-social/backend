package com.app.modules.recommendation.rebuild;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts a Gorse rebuild when an operator sets {@code GORSE_REBUILD=true} and names the run with
 * {@code GORSE_REBUILD_TOKEN}.
 *
 * <p>Not tied to the {@code seed} profile, because it is a production operator tool; it is switched
 * on the way {@code SEED_DATA} is. The run goes on a daemon thread after startup, so the health
 * check and the deployment are never delayed by it, and there is no HTTP endpoint.
 *
 * <p>The token decides what happens. With none, nothing runs. With one no run has used, a run
 * starts at the first phase. With a run that is {@code RUNNING} or {@code FAILED}, it resumes from
 * its phase and checkpoint, which is what a crash or a redeploy leaves behind. With a finished run
 * ({@code DONE} or {@code FAILED_VERIFICATION}) nothing happens, so leaving the toggle set across a
 * restart repeats nothing, and a new rebuild needs a new token.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "GORSE_REBUILD", havingValue = "true")
@RequiredArgsConstructor
public class GorseRebuildRunner {

    private final GorseRebuildService service;
    private final GorseRebuildRunRepository runs;

    @Value("${GORSE_REBUILD_TOKEN:}")
    private String token;

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread thread = new Thread(this::runOnce, "gorse-rebuild");
        thread.setDaemon(true);
        thread.start();
    }

    /** Decides from the token what to run, and runs it on the calling thread. */
    void runOnce() {
        String name = token == null ? "" : token.strip();
        if (name.isEmpty()) {
            log.error(
                    "[gorse-rebuild] GORSE_REBUILD is set but GORSE_REBUILD_TOKEN is empty; nothing"
                            + " will run. Name the run with a token no earlier rebuild used.");
            return;
        }
        try {
            Optional<GorseRebuildRun> existing = runs.findByToken(name);
            if (existing.isPresent() && existing.get().getStatus().isFinal()) {
                log.info(
                        "[gorse-rebuild] token={} already finished with status {}; nothing to do."
                                + " A new rebuild needs a new token.",
                        name,
                        existing.get().getStatus());
                return;
            }
            GorseRebuildRun run;
            if (existing.isPresent()) {
                run = existing.get();
                log.info(
                        "[gorse-rebuild] token={} resuming phase={} checkpoint={}",
                        name,
                        run.getPhase(),
                        run.getCheckpointId());
            } else {
                run = runs.create(name);
                log.info("[gorse-rebuild] token={} starting phase={}", name, run.getPhase());
            }
            service.execute(run);
        } catch (RuntimeException e) {
            log.error("[gorse-rebuild] token={} could not start", name, e);
        }
    }
}
