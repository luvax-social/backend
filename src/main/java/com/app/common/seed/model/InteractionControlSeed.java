package com.app.common.seed.model;

import java.util.List;

/**
 * Parsed content of {@code safety/interaction_control.json}: the mutes, show-fewer and
 * not-interested preferences, interaction limits, Hidden Words entries and filtered comments the
 * seed writes.
 */
public record InteractionControlSeed(
        List<MuteSeed> mutes,
        List<ShowFewerSeed> showFewer,
        List<NotInterestedSeed> notInterested,
        List<InteractionLimitSeed> interactionLimits,
        List<HiddenWordSeed> hiddenWords,
        List<HiddenCommentSeed> hiddenComments) {

    public InteractionControlSeed {
        mutes = mutes == null ? List.of() : List.copyOf(mutes);
        showFewer = showFewer == null ? List.of() : List.copyOf(showFewer);
        notInterested = notInterested == null ? List.of() : List.copyOf(notInterested);
        interactionLimits = interactionLimits == null ? List.of() : List.copyOf(interactionLimits);
        hiddenWords = hiddenWords == null ? List.of() : List.copyOf(hiddenWords);
        hiddenComments = hiddenComments == null ? List.of() : List.copyOf(hiddenComments);
    }
}
