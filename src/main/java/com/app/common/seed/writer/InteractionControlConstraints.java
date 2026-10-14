package com.app.common.seed.writer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.HiddenCommentSeed;
import com.app.common.seed.model.InteractionControlSeed;
import com.app.common.seed.model.InteractionLimitSeed;
import com.app.common.seed.model.MuteSeed;
import com.app.common.seed.model.NotInterestedSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.ShowFewerSeed;

/**
 * The pairs of accounts whose relationship the interaction control content depends on, so the
 * generated follow and block graph can be shaped around them instead of contradicting them.
 *
 * <p>A mute, a show-fewer mark or a filtered comment only makes sense between accounts that are not
 * blocked, a comment hidden because its author is not a follower only makes sense when that author
 * really does not follow the post owner, and a recent follower has to follow. The graph writer is
 * the one place that decides follows and blocks, so it asks this class for the constraints before
 * it generates anything.
 */
final class InteractionControlConstraints {

    /**
     * Days between a platform Hidden Words entry being created and being edited or deleted, which
     * the graph writer and the moderation writer both use so an entry's row and its audit history
     * agree.
     */
    static final int PLATFORM_WORD_CHANGE_DELAY_DAYS = 10;

    private InteractionControlConstraints() {}

    /**
     * Unordered pairs the block generator must not pick, written {@code a|b} with the usernames in
     * ascending order.
     */
    static Set<String> protectedPairs(SeedContent content) {
        InteractionControlSeed seed = content.interactionControl();
        Map<String, String> authorByPostId =
                content.posts().stream()
                        .collect(Collectors.toMap(PostSeed::id, PostSeed::authorUsername));
        Set<String> pairs = new HashSet<>();
        for (MuteSeed mute : seed.mutes()) {
            pairs.add(unordered(mute.viewer(), mute.target()));
        }
        for (ShowFewerSeed entry : seed.showFewer()) {
            pairs.add(unordered(entry.viewer(), entry.author()));
        }
        for (NotInterestedSeed entry : seed.notInterested()) {
            pairs.add(unordered(entry.viewer(), authorByPostId.get(entry.postSeedId())));
        }
        for (InteractionLimitSeed limit : seed.interactionLimits()) {
            for (String follower : limit.recentFollowers()) {
                pairs.add(unordered(follower, limit.owner()));
            }
        }
        for (HiddenCommentSeed comment : seed.hiddenComments()) {
            pairs.add(unordered(comment.commenter(), comment.postOwner()));
        }
        return pairs;
    }

    /**
     * Directed follows the generator must not create, written {@code follower->following}: a
     * comment hidden by a limit on non-followers needs an author who is not a follower.
     */
    static Set<String> forbiddenFollows(SeedContent content) {
        InteractionControlSeed seed = content.interactionControl();
        Set<String> recentFollowPairs = recentFollowPairs(seed);
        Set<String> forbidden = new HashSet<>();
        for (HiddenCommentSeed comment : seed.hiddenComments()) {
            String pair = comment.commenter() + "->" + comment.postOwner();
            if ("limited_interaction".equals(comment.reason())
                    && !recentFollowPairs.contains(pair)) {
                forbidden.add(pair);
            }
        }
        return forbidden;
    }

    /**
     * Accepted follows that must exist, as {@code {follower, following}}: whoever mutes or shows
     * fewer of an account follows it, since both are controls over a followed account's content.
     */
    static List<String[]> requiredFollows(SeedContent content) {
        InteractionControlSeed seed = content.interactionControl();
        List<String[]> required = new ArrayList<>();
        for (MuteSeed mute : seed.mutes()) {
            if (!mute.undone()) {
                required.add(new String[] {mute.viewer(), mute.target()});
            }
        }
        for (ShowFewerSeed entry : seed.showFewer()) {
            if (!entry.undone()) {
                required.add(new String[] {entry.viewer(), entry.author()});
            }
        }
        return required;
    }

    /** Follower-to-owner pairs of every recent follower an interaction limit names. */
    static Set<String> recentFollowPairs(InteractionControlSeed seed) {
        return seed.interactionLimits().stream()
                .flatMap(
                        limit ->
                                limit.recentFollowers().stream()
                                        .map(follower -> follower + "->" + limit.owner()))
                .collect(Collectors.toSet());
    }

    static String unordered(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
}
