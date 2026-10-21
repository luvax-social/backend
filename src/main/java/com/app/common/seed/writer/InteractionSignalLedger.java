package com.app.common.seed.writer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.app.common.seed.outbox.SeedOutboxBatchWriter.UserEventImport;

/**
 * Carries what the interaction control writers know about the rows they wrote to the writers that
 * run after them.
 *
 * <p>A mute, a hidden comment or a reaction is a row, and each also has a behavioural signal in the
 * analytics tier ({@code account_mute}, {@code comment_hidden}, {@code message_reaction}). The
 * writer that inserts the row is the one place that knows the row's id and the time it was dated,
 * and for an undone preference it is the only place that knows the preference ever existed, so it
 * records the signal here and {@link AnalyticsSeedWriter} imports every one of them at the end of
 * the run. A signal is therefore never invented for something that did not happen.
 *
 * <p>It also remembers which comments an owner released after the filter hid them. Such a comment
 * is stored approved, so without this {@link NotificationSeedWriter} would notify the post owner
 * about a comment the owner had already reviewed and released, which the real unhide never does.
 */
@Component
@Profile("seed & (dev | prod)")
public class InteractionSignalLedger {

    private final List<UserEventImport> signals = new ArrayList<>();
    private final Set<UUID> unhiddenCommentIds = new HashSet<>();
    private final Map<String, UUID> messageIds = new HashMap<>();

    /**
     * The identifier of a signal, derived from what it describes so that recording the same signal
     * twice (a repeated run over the same content) imports it once.
     */
    public static UUID signalId(String kind, String key) {
        return UUID.nameUUIDFromBytes(
                ("seed_signal:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Records one signal for {@link AnalyticsSeedWriter} to import. */
    public synchronized void record(UserEventImport signal) {
        signals.add(signal);
    }

    /**
     * Remembers the message a conversation's {@code index}th scripted message became, so a later
     * writer finds it by the position the content names. Looking it up by timestamp instead is
     * fragile: two scripted messages a minute apart can swap places under the random jitter the
     * timeline adds.
     */
    public synchronized void rememberMessage(String conversationSeedId, int index, UUID messageId) {
        messageIds.put(conversationSeedId + "#" + index, messageId);
    }

    /** The message remembered for a conversation position, or null when none was. */
    public synchronized UUID messageId(String conversationSeedId, int index) {
        return messageIds.get(conversationSeedId + "#" + index);
    }

    /** Remembers a comment the owner released, so no notification is written for it. */
    public synchronized void markUnhidden(UUID commentId) {
        unhiddenCommentIds.add(commentId);
    }

    /** Comments the owner released after they were hidden. */
    public synchronized Set<UUID> unhiddenCommentIds() {
        return Set.copyOf(unhiddenCommentIds);
    }

    /** Returns every recorded signal and forgets them, so a signal is imported exactly once. */
    public synchronized List<UserEventImport> drain() {
        List<UserEventImport> drained = new ArrayList<>(signals);
        signals.clear();
        return drained;
    }

    /** Forgets everything, for the start of a run. */
    public synchronized void clear() {
        signals.clear();
        unhiddenCommentIds.clear();
        messageIds.clear();
    }
}
