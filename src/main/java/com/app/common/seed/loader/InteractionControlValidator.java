package com.app.common.seed.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.HiddenCommentSeed;
import com.app.common.seed.model.HiddenWordSeed;
import com.app.common.seed.model.InteractionControlSeed;
import com.app.common.seed.model.InteractionLimitSeed;
import com.app.common.seed.model.MessageRequestSeed;
import com.app.common.seed.model.MuteSeed;
import com.app.common.seed.model.NotInterestedSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.ReactionSeed;
import com.app.common.seed.model.ReplyParentSeed;
import com.app.common.seed.model.ShowFewerSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.message.util.ReactionEmojiValidator;

/**
 * Checks the cross-file references and the invariants of {@code safety/interaction_control.json}
 * that the database would otherwise reject halfway through a seed run, or accept and leave
 * inconsistent: a mute of a private account, a limit duration outside the presets, a hidden comment
 * whose reason contradicts its parent.
 */
final class InteractionControlValidator {

    private static final String FILE = "safety/interaction_control.json";
    private static final Set<Integer> LIMIT_DURATIONS = Set.of(1, 3, 7, 14, 28);
    private static final Set<String> HIDDEN_REASONS =
            Set.of("hidden_words", "limited_interaction", "hidden_parent");
    private static final Set<String> REQUEST_STATUSES =
            Set.of("pending", "hidden", "declined", "accepted");
    private static final int MAX_PHRASE_LENGTH = 64;
    private static final int MAX_PHRASE_TOKENS = 6;
    // The audit history of an edited or deleted platform entry sits after its creation, so the
    // entry must have been created far enough back to leave room for it.
    private static final int MIN_PLATFORM_ENTRY_AGE_DAYS = 12;

    private final Map<String, UserSeed> userByUsername = new HashMap<>();
    private final Map<String, PostSeed> postById = new HashMap<>();
    private final Map<String, List<PostSeed>> publishedPostsByAuthor = new HashMap<>();
    private final Map<String, ConversationSeed> conversationById = new HashMap<>();

    private InteractionControlValidator(
            List<UserSeed> users, List<PostSeed> posts, List<ConversationSeed> conversations) {
        for (ConversationSeed conversation : conversations) {
            conversationById.put(conversation.id(), conversation);
        }
        for (UserSeed user : users) {
            userByUsername.put(user.username(), user);
        }
        for (PostSeed post : posts) {
            postById.put(post.id(), post);
            if ("published".equals(post.status())) {
                publishedPostsByAuthor
                        .computeIfAbsent(post.authorUsername(), key -> new ArrayList<>())
                        .add(post);
            }
        }
    }

    static void validate(
            InteractionControlSeed content,
            List<UserSeed> users,
            List<PostSeed> posts,
            List<ConversationSeed> conversations) {
        InteractionControlValidator validator =
                new InteractionControlValidator(users, posts, conversations);
        validator.validateMutes(content.mutes());
        validator.validateShowFewer(content.showFewer());
        validator.validateNotInterested(content.notInterested());
        validator.validateLimits(content.interactionLimits());
        validator.validateHiddenWords(content.hiddenWords());
        validator.validateHiddenComments(content.hiddenComments());
        validator.validateMessageRequests(content.messageRequests());
        validator.validateReplyParents(content.replyParents());
        validator.validateReactions(content.reactions());
    }

    private void validateMutes(List<MuteSeed> mutes) {
        Set<String> pairs = new HashSet<>();
        for (MuteSeed mute : mutes) {
            String where = "mutes entry '" + mute.viewer() + "' -> '" + mute.target() + "'";
            requireActiveUser(mute.viewer(), where);
            requirePublicActiveUser(mute.target(), where);
            requireDistinct(mute.viewer(), mute.target(), where);
            if (!mute.mutePosts() && !mute.muteStories()) {
                throw fail(where + " mutes neither posts nor stories");
            }
            requireNewKey(pairs, mute.viewer() + "->" + mute.target(), where);
        }
    }

    private void validateShowFewer(List<ShowFewerSeed> entries) {
        Set<String> pairs = new HashSet<>();
        for (ShowFewerSeed entry : entries) {
            String where = "show_fewer entry '" + entry.viewer() + "' -> '" + entry.author() + "'";
            requireActiveUser(entry.viewer(), where);
            requirePublicActiveUser(entry.author(), where);
            requireDistinct(entry.viewer(), entry.author(), where);
            requireNewKey(pairs, entry.viewer() + "->" + entry.author(), where);
        }
    }

    private void validateNotInterested(List<NotInterestedSeed> entries) {
        Set<String> pairs = new HashSet<>();
        for (NotInterestedSeed entry : entries) {
            String where =
                    "not_interested entry '" + entry.viewer() + "' -> '" + entry.postSeedId() + "'";
            requireActiveUser(entry.viewer(), where);
            PostSeed post = postById.get(entry.postSeedId());
            if (post == null || !"published".equals(post.status())) {
                throw fail(where + " does not name a published post in posts.json");
            }
            requireDistinct(entry.viewer(), post.authorUsername(), where);
            requireNewKey(pairs, entry.viewer() + "->" + entry.postSeedId(), where);
        }
    }

    private void validateLimits(List<InteractionLimitSeed> limits) {
        Set<String> owners = new HashSet<>();
        for (InteractionLimitSeed limit : limits) {
            String where = "interaction_limits entry for '" + limit.owner() + "'";
            requireActiveUser(limit.owner(), where);
            requireNewKey(owners, limit.owner(), where);
            if (!limit.limitNonFollowers() && !limit.limitRecentFollowers()) {
                throw fail(where + " limits neither non-followers nor recent followers");
            }
            if (!LIMIT_DURATIONS.contains(limit.durationDays())) {
                throw fail(where + " has duration_days " + limit.durationDays() + ", not a preset");
            }
            if (limit.startedDaysAgo() < 0) {
                throw fail(where + " starts in the future");
            }
            if (!limit.recentFollowers().isEmpty() && !limit.limitRecentFollowers()) {
                throw fail(where + " lists recent_followers without limiting recent followers");
            }
            Set<String> followers = new HashSet<>();
            for (String follower : limit.recentFollowers()) {
                requireActiveUser(follower, where);
                requireDistinct(follower, limit.owner(), where);
                requireNewKey(followers, follower, where);
            }
        }
    }

    private void validateHiddenWords(List<HiddenWordSeed> words) {
        Set<String> seen = new HashSet<>();
        for (HiddenWordSeed word : words) {
            String where = "hidden_words entry '" + word.phrase() + "'";
            requireValidPhrase(word.phrase(), where);
            String scope = word.owner() == null ? "<system>" : word.owner();
            if (word.owner() != null) {
                requireActiveUser(word.owner(), where);
                if (word.previousPhrase() != null || word.deleted() || word.admin() != null) {
                    throw fail(where + " is personal but carries platform audit fields");
                }
            } else {
                UserSeed admin = userByUsername.get(word.admin());
                if (admin == null || !"admin".equals(admin.role())) {
                    throw fail(where + " names admin '" + word.admin() + "', not an administrator");
                }
                if (word.previousPhrase() != null) {
                    requireValidPhrase(word.previousPhrase(), where + " previous_phrase");
                }
                if (word.deleted() && word.previousPhrase() != null) {
                    throw fail(where + " is both deleted and edited");
                }
                if (word.daysAgo() < MIN_PLATFORM_ENTRY_AGE_DAYS) {
                    throw fail(
                            where
                                    + " needs days_ago of at least "
                                    + MIN_PLATFORM_ENTRY_AGE_DAYS
                                    + " to fit its audit history");
                }
            }
            requireNewKey(
                    seen, scope + "|" + TextMatchNormalizer.normalizePhrase(word.phrase()), where);
        }
    }

    private void validateHiddenComments(List<HiddenCommentSeed> comments) {
        Map<String, HiddenCommentSeed> byId = new HashMap<>();
        for (HiddenCommentSeed comment : comments) {
            String where = "hidden_comments entry '" + comment.id() + "'";
            if (byId.put(comment.id(), comment) != null) {
                throw fail(where + " is declared twice");
            }
            requireActiveUser(comment.commenter(), where);
            requireActiveUser(comment.postOwner(), where);
            requireDistinct(comment.commenter(), comment.postOwner(), where);
            int publishedPosts =
                    publishedPostsByAuthor.getOrDefault(comment.postOwner(), List.of()).size();
            if (comment.postIndex() < 0 || comment.postIndex() >= publishedPosts) {
                throw fail(
                        where
                                + " names post_index "
                                + comment.postIndex()
                                + " but the owner has "
                                + publishedPosts
                                + " published post(s)");
            }
            if (!HIDDEN_REASONS.contains(comment.reason())) {
                throw fail(where + " has unknown reason '" + comment.reason() + "'");
            }
            if (comment.text() == null || comment.text().isBlank()) {
                throw fail(where + " has no text");
            }
            boolean reply = comment.replyTo() != null;
            if (reply != "hidden_parent".equals(comment.reason())) {
                throw fail(where + " must use reason hidden_parent exactly when reply_to is set");
            }
            if (reply) {
                validateReplyParent(comment, byId.get(comment.replyTo()), where);
            }
        }
    }

    // A reply is hidden only because its parent is, and the author of a hidden comment is the one
    // account that can still answer it, so the parent must be an earlier, still-hidden comment of
    // the same author on the same post.
    private void validateReplyParent(
            HiddenCommentSeed reply, HiddenCommentSeed parent, String where) {
        if (parent == null) {
            throw fail(
                    where
                            + " replies to '"
                            + reply.replyTo()
                            + "', which must be declared earlier");
        }
        if (reply.unhidden()
                || parent.unhidden()
                || !parent.postOwner().equals(reply.postOwner())
                || parent.postIndex() != reply.postIndex()
                || !parent.commenter().equals(reply.commenter())) {
            throw fail(
                    where
                            + " must reply to a still-hidden comment of its own author on the same"
                            + " post");
        }
    }

    // A request is a stranger's first message to an account that allows everyone to write to it, so
    // no two accounts may already share a conversation, and the pair is unique across requests.
    private void validateMessageRequests(List<MessageRequestSeed> requests) {
        Set<String> pairs = new HashSet<>();
        for (ConversationSeed conversation : conversationById.values()) {
            pairs.add(
                    unordered(
                            conversation.participants().get(0),
                            conversation.participants().get(1)));
        }
        Set<String> ids = new HashSet<>();
        for (MessageRequestSeed request : requests) {
            String where = "message_requests entry '" + request.id() + "'";
            requireNewKey(ids, request.id(), where);
            requireActiveUser(request.requester(), where);
            requireActiveUser(request.recipient(), where);
            requireDistinct(request.requester(), request.recipient(), where);
            if (!REQUEST_STATUSES.contains(request.status())) {
                throw fail(where + " has unknown status '" + request.status() + "'");
            }
            if (!"everyone".equals(userByUsername.get(request.recipient()).messagePermission())) {
                throw fail(
                        where
                                + " is addressed to an account that does not allow everyone to write to it");
            }
            if (request.text() == null || request.text().isBlank()) {
                throw fail(where + " has no text");
            }
            boolean accepted = "accepted".equals(request.status());
            if (accepted != (request.replyText() != null)) {
                throw fail(where + " must carry reply_text exactly when it is accepted");
            }
            if (request.daysAgo() < 1) {
                throw fail(where + " must be at least a day old");
            }
            requireNewKey(
                    pairs,
                    unordered(request.requester(), request.recipient()),
                    where + " (its two accounts already share a conversation or a request)");
        }
    }

    private void validateReplyParents(List<ReplyParentSeed> parents) {
        Set<String> children = new HashSet<>();
        for (ReplyParentSeed parent : parents) {
            String where =
                    "reply_parents entry '"
                            + parent.conversationId()
                            + "#"
                            + parent.messageIndex()
                            + "'";
            ConversationSeed conversation = requireConversation(parent.conversationId(), where);
            int size = conversation.messages().size();
            if (parent.messageIndex() <= 0
                    || parent.messageIndex() >= size
                    || parent.replyToIndex() < 0
                    || parent.replyToIndex() >= parent.messageIndex()) {
                throw fail(
                        where
                                + " must quote an earlier message of a conversation with "
                                + size
                                + " messages");
            }
            requireNewKey(children, parent.conversationId() + "#" + parent.messageIndex(), where);
        }
    }

    private void validateReactions(List<ReactionSeed> reactions) {
        Set<String> seen = new HashSet<>();
        for (ReactionSeed reaction : reactions) {
            String where =
                    "reactions entry '"
                            + reaction.conversationId()
                            + "#"
                            + reaction.messageIndex()
                            + "' by '"
                            + reaction.reactor()
                            + "'";
            ConversationSeed conversation = requireConversation(reaction.conversationId(), where);
            if (reaction.messageIndex() < 0
                    || reaction.messageIndex() >= conversation.messages().size()) {
                throw fail(where + " names a message the conversation does not have");
            }
            if (!conversation.participants().contains(reaction.reactor())) {
                throw fail(where + " is not by a participant of the conversation");
            }
            if (reaction.emoji() == null
                    || !ReactionEmojiValidator.isSingleEmoji(reaction.emoji())) {
                throw fail(where + " does not carry exactly one emoji");
            }
            requireNewKey(
                    seen,
                    reaction.conversationId()
                            + "#"
                            + reaction.messageIndex()
                            + "#"
                            + reaction.reactor(),
                    where);
        }
    }

    private ConversationSeed requireConversation(String id, String where) {
        ConversationSeed conversation = conversationById.get(id);
        if (conversation == null) {
            throw fail(
                    where
                            + " names conversation '"
                            + id
                            + "', which does not exist in conversations.json");
        }
        return conversation;
    }

    private static String unordered(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private void requireValidPhrase(String phrase, String where) {
        if (phrase == null || phrase.isBlank() || phrase.length() > MAX_PHRASE_LENGTH) {
            throw fail(where + " has an empty or over-long phrase");
        }
        int tokens = TextMatchNormalizer.tokens(phrase).size();
        if (tokens < 1 || tokens > MAX_PHRASE_TOKENS) {
            throw fail(
                    where
                            + " normalises to "
                            + tokens
                            + " token(s), outside 1 to "
                            + MAX_PHRASE_TOKENS);
        }
    }

    private void requireActiveUser(String username, String where) {
        UserSeed user = userByUsername.get(username);
        if (user == null) {
            throw fail(where + " names '" + username + "', which does not exist in users.json");
        }
        if (!"active".equals(user.status())) {
            throw fail(where + " names '" + username + "', whose status is " + user.status());
        }
    }

    private void requirePublicActiveUser(String username, String where) {
        requireActiveUser(username, where);
        if (userByUsername.get(username).isPrivate()) {
            throw fail(where + " names private account '" + username + "'");
        }
    }

    private void requireDistinct(String a, String b, String where) {
        if (a.equals(b)) {
            throw fail(where + " names the same account on both sides");
        }
    }

    private void requireNewKey(Set<String> seen, String key, String where) {
        if (!seen.add(key)) {
            throw fail(where + " duplicates an earlier entry");
        }
    }

    private IllegalStateException fail(String message) {
        return new IllegalStateException(FILE + ": " + message);
    }
}
