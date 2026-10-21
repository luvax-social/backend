package com.app.common.seed.model;

import java.util.List;

/**
 * Parsed content of {@code safety/interaction_control.json}: the mutes, show-fewer and
 * not-interested preferences, interaction limits, Hidden Words entries and filtered comments, and
 * the message requests, reply quotes and reactions the seed writes, and the reports and moderation
 * decisions about some of those reactions.
 */
public record InteractionControlSeed(
        List<MuteSeed> mutes,
        List<ShowFewerSeed> showFewer,
        List<NotInterestedSeed> notInterested,
        List<InteractionLimitSeed> interactionLimits,
        List<HiddenWordSeed> hiddenWords,
        List<HiddenCommentSeed> hiddenComments,
        List<MessageRequestSeed> messageRequests,
        List<ReplyParentSeed> replyParents,
        List<ReactionSeed> reactions,
        List<ReactionReportSeed> reactionModeration) {

    public InteractionControlSeed {
        mutes = mutes == null ? List.of() : List.copyOf(mutes);
        showFewer = showFewer == null ? List.of() : List.copyOf(showFewer);
        notInterested = notInterested == null ? List.of() : List.copyOf(notInterested);
        interactionLimits = interactionLimits == null ? List.of() : List.copyOf(interactionLimits);
        hiddenWords = hiddenWords == null ? List.of() : List.copyOf(hiddenWords);
        hiddenComments = hiddenComments == null ? List.of() : List.copyOf(hiddenComments);
        messageRequests = messageRequests == null ? List.of() : List.copyOf(messageRequests);
        replyParents = replyParents == null ? List.of() : List.copyOf(replyParents);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        reactionModeration =
                reactionModeration == null ? List.of() : List.copyOf(reactionModeration);
    }
}
