package com.app.common.seed.loader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.app.common.seed.model.BadgeRevocationSeed;
import com.app.common.seed.model.BadgeSeed;
import com.app.common.seed.model.CommentPoolSeed;
import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.HashtagSeed;
import com.app.common.seed.model.LegacyGrantSeed;
import com.app.common.seed.model.MediaManifestEntry;
import com.app.common.seed.model.MessageSeed;
import com.app.common.seed.model.ModerationCaseSeed;
import com.app.common.seed.model.ModerationSupplementaryAction;
import com.app.common.seed.model.ModerationSupplementaryReport;
import com.app.common.seed.model.PersonaSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.SupportTicketPoolSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.model.VerificationTicketSeed;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Reads and deserializes every seed JSON file under {@code src/main/resources/seed/} into typed
 * records, then validates the cross-file references those files carry as bare strings (usernames,
 * media manifest ids, topic tags).
 *
 * <p>Not wired as a Spring bean: it has no runtime dependency, so every writer and {@code
 * SeedRunner} (Task 8) construct it directly.
 */
public class SeedDataLoader {

    private static final String DEFAULT_CLASSPATH_BASE = "seed";

    private final ObjectMapper objectMapper;
    private final String classpathBase;

    public SeedDataLoader() {
        this(DEFAULT_CLASSPATH_BASE);
    }

    // Lets tests point the loader at a fixture directory carrying a deliberately
    // broken file, without touching the real seed content under src/main/resources/seed/.
    public SeedDataLoader(String classpathBase) {
        this.classpathBase = classpathBase;
        this.objectMapper =
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Loads all eight seed JSON files and validates every cross-file reference they carry.
     *
     * @throws IllegalStateException if a file is malformed JSON, or if a post's author, a media
     *     reference, a comment-pool topic, or a conversation participant does not resolve against
     *     the other loaded files
     */
    public SeedContent load() {
        List<PersonaSeed> personas =
                readList("personas.json", PersonasFile.class, PersonasFile::personas);
        List<UserSeed> users = readList("users.json", UsersFile.class, UsersFile::users);
        List<HashtagSeed> hashtags =
                readList("content/hashtags.json", HashtagsFile.class, HashtagsFile::hashtags);
        List<PostSeed> posts = readList("content/posts.json", PostsFile.class, PostsFile::posts);
        CommentPoolSeed commentPools = readCommentPools();
        List<ConversationSeed> conversations =
                readList(
                        "messaging/conversations.json",
                        ConversationsFile.class,
                        ConversationsFile::conversations);
        ModerationCasesFile moderationCasesFile =
                readFile("moderation/moderation_cases.json", ModerationCasesFile.class);
        List<ModerationCaseSeed> moderationCases = moderationCasesFile.cases();
        List<ModerationSupplementaryAction> supplementaryActions =
                nullToEmpty(moderationCasesFile.supplementaryActions());
        List<ModerationSupplementaryReport> supplementaryReports =
                nullToEmpty(moderationCasesFile.supplementaryReports());
        List<MediaManifestEntry> mediaManifest = readMediaManifest();
        SupportTicketPoolSeed supportTicketPools =
                readFile("support/support_ticket_pools.json", SupportTicketPoolSeed.class);
        BadgeSeed badges = readFile("verification/badges.json", BadgeSeed.class);

        Set<String> usernames = users.stream().map(UserSeed::username).collect(Collectors.toSet());
        Set<String> mediaIds =
                mediaManifest.stream().map(MediaManifestEntry::id).collect(Collectors.toSet());
        Set<String> commentPoolTopics = commentPools.pools().keySet();

        Set<String> publishedPostIds =
                posts.stream()
                        .filter(p -> "published".equals(p.status()))
                        .map(PostSeed::id)
                        .collect(Collectors.toSet());

        validatePostAuthorsExist(posts, usernames);
        validatePostMediaRefsResolve(posts, mediaIds);
        validatePostTopicsHaveCommentPools(posts, commentPoolTopics);
        validateConversationParticipantsExist(conversations, usernames);
        validateMessageReferencesResolve(conversations, moderationCases, supplementaryActions);
        validateMessageMediaAndShareReferencesResolve(
                conversations, usernames, mediaIds, publishedPostIds);
        validateConversationParticipantScopedFieldsResolve(conversations);
        validateSupplementaryReportsAreUnique(supplementaryReports);
        validateBadgeSubjectsExist(badges, users);

        return new SeedContent(
                personas,
                users,
                hashtags,
                posts,
                commentPools,
                conversations,
                moderationCases,
                supplementaryActions,
                supplementaryReports,
                mediaManifest,
                supportTicketPools,
                badges);
    }

    // Mirrors validatePostAuthorsExist: a badge names its subject as a bare username, and a
    // typo would otherwise surface as a silently skipped grant rather than a load failure. The
    // role check is here rather than in the writer because
    // AdminAuthorizationService.assertMayDecideVerification refuses an administrator as a subject,
    // so authored content that names one is wrong content, not a runtime condition to handle.
    private void validateBadgeSubjectsExist(BadgeSeed badges, List<UserSeed> users) {
        Map<String, String> roleByUsername = new HashMap<>();
        for (UserSeed user : users) {
            roleByUsername.put(user.username(), user.role());
        }
        List<String> subjects = new ArrayList<>();
        for (VerificationTicketSeed ticket : badges.verificationTickets()) {
            subjects.add(ticket.username());
        }
        for (LegacyGrantSeed grant : badges.legacyGrants()) {
            subjects.add(grant.username());
        }
        for (BadgeRevocationSeed revocation : badges.revocations()) {
            subjects.add(revocation.username());
        }
        for (String username : subjects) {
            String role = roleByUsername.get(username);
            if (role == null) {
                throw new IllegalStateException(
                        "verification/badges.json: names account '"
                                + username
                                + "' which does not exist in users.json");
            }
            if (!"user".equals(role)) {
                throw new IllegalStateException(
                        "verification/badges.json: account '"
                                + username
                                + "' has role '"
                                + role
                                + "'; a verification subject may be neither the acting staff"
                                + " member nor an administrator");
            }
        }
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private void validatePostAuthorsExist(List<PostSeed> posts, Set<String> usernames) {
        for (PostSeed post : posts) {
            if (!usernames.contains(post.authorUsername())) {
                throw new IllegalStateException(
                        "posts.json: post '"
                                + post.id()
                                + "' has author_username '"
                                + post.authorUsername()
                                + "' which does not exist in users.json");
            }
        }
    }

    private void validatePostMediaRefsResolve(List<PostSeed> posts, Set<String> mediaIds) {
        for (PostSeed post : posts) {
            for (String mediaRef : post.mediaRefs()) {
                if (!mediaIds.contains(mediaRef)) {
                    throw new IllegalStateException(
                            "posts.json: post '"
                                    + post.id()
                                    + "' references media_refs entry '"
                                    + mediaRef
                                    + "' which does not exist in media_manifest.json");
                }
            }
        }
    }

    private void validatePostTopicsHaveCommentPools(
            List<PostSeed> posts, Set<String> commentPoolTopics) {
        for (PostSeed post : posts) {
            for (String topicTag : post.topicTags()) {
                if (!commentPoolTopics.contains(topicTag)) {
                    throw new IllegalStateException(
                            "posts.json: post '"
                                    + post.id()
                                    + "' references topic_tags entry '"
                                    + topicTag
                                    + "' which has no pool in comment_pools.json");
                }
            }
        }
    }

    private void validateConversationParticipantsExist(
            List<ConversationSeed> conversations, Set<String> usernames) {
        for (ConversationSeed conversation : conversations) {
            for (String participant : conversation.participants()) {
                if (!usernames.contains(participant)) {
                    throw new IllegalStateException(
                            "conversations.json: conversation '"
                                    + conversation.id()
                                    + "' has participant '"
                                    + participant
                                    + "' which does not exist in users.json");
                }
            }
        }
    }

    // Every conversation-targeting reference (a narrative case's timeline, or a supplementary
    // action) names its message by (conversation id, 0-based index) rather than a message id,
    // so a typo or a stale index silently no-ops at write time unless caught here.
    private void validateMessageReferencesResolve(
            List<ConversationSeed> conversations,
            List<ModerationCaseSeed> moderationCases,
            List<ModerationSupplementaryAction> supplementaryActions) {
        Map<String, Integer> messageCountByConversationId =
                conversations.stream()
                        .collect(Collectors.toMap(ConversationSeed::id, c -> c.messages().size()));

        for (ModerationSupplementaryAction action : supplementaryActions) {
            if (action.targetConversationId() != null) {
                requireResolvableMessageRef(
                        messageCountByConversationId,
                        action.targetConversationId(),
                        action.targetMessageIndex(),
                        "moderation_cases.json: supplementary_actions entry at '"
                                + action.at()
                                + "'");
            }
        }
        for (ModerationCaseSeed moderationCase : moderationCases) {
            for (Map<String, Object> event : moderationCase.timeline()) {
                Object conversationId = event.get("target_conversation_id");
                if (conversationId != null) {
                    requireResolvableMessageRef(
                            messageCountByConversationId,
                            (String) conversationId,
                            (Integer) event.get("target_message_index"),
                            "moderation_cases.json: case '"
                                    + moderationCase.id()
                                    + "' timeline entry at '"
                                    + event.get("at")
                                    + "'");
                }
            }
        }
    }

    private void requireResolvableMessageRef(
            Map<String, Integer> messageCountByConversationId,
            String conversationId,
            Integer messageIndex,
            String source) {
        Integer messageCount = messageCountByConversationId.get(conversationId);
        if (messageCount == null) {
            throw new IllegalStateException(
                    source
                            + " references conversation '"
                            + conversationId
                            + "' which does not exist in conversations.json");
        }
        if (messageIndex == null || messageIndex < 0 || messageIndex >= messageCount) {
            throw new IllegalStateException(
                    source
                            + " references message index "
                            + messageIndex
                            + " of conversation '"
                            + conversationId
                            + "' which only has "
                            + messageCount
                            + " message(s)");
        }
    }

    // A message's media_ref/shared_post_seed_id/shared_story_owner are bare strings the same way a
    // post's media_refs and a conversation's participants are - validated here so an authoring typo
    // fails the load instead of surfacing as a write-time IllegalStateException deep inside
    // MessageSeedWriter. shared_story_owner is checked only for username existence: a story's
    // liveness is a runtime property StorySeedWriter decides, which this static loader has no way
    // to predict.
    private void validateMessageMediaAndShareReferencesResolve(
            List<ConversationSeed> conversations,
            Set<String> usernames,
            Set<String> mediaIds,
            Set<String> publishedPostIds) {
        for (ConversationSeed conversation : conversations) {
            for (MessageSeed message : conversation.messages()) {
                if (message.mediaRef() != null && !mediaIds.contains(message.mediaRef())) {
                    throw new IllegalStateException(
                            "conversations.json: conversation '"
                                    + conversation.id()
                                    + "' has a message with media_ref '"
                                    + message.mediaRef()
                                    + "' which does not exist in media_manifest.json");
                }
                if (message.sharedPostSeedId() != null
                        && !publishedPostIds.contains(message.sharedPostSeedId())) {
                    throw new IllegalStateException(
                            "conversations.json: conversation '"
                                    + conversation.id()
                                    + "' has a post_share message referencing shared_post_seed_id '"
                                    + message.sharedPostSeedId()
                                    + "' which is not a published post in posts.json");
                }
                if (message.sharedStoryOwner() != null
                        && !usernames.contains(message.sharedStoryOwner())) {
                    throw new IllegalStateException(
                            "conversations.json: conversation '"
                                    + conversation.id()
                                    + "' has a story_share message referencing"
                                    + " shared_story_owner '"
                                    + message.sharedStoryOwner()
                                    + "' which does not exist in users.json");
                }
            }
        }
    }

    // manuallyUnreadFor and nicknames both name a participant of the conversation they appear on -
    // manuallyUnreadFor directly, nicknames via its map keys (the owning participant, not the
    // nicknamed one).
    private void validateConversationParticipantScopedFieldsResolve(
            List<ConversationSeed> conversations) {
        for (ConversationSeed conversation : conversations) {
            Set<String> participants = new HashSet<>(conversation.participants());
            if (conversation.manuallyUnreadFor() != null) {
                for (String username : conversation.manuallyUnreadFor()) {
                    if (!participants.contains(username)) {
                        throw new IllegalStateException(
                                "conversations.json: conversation '"
                                        + conversation.id()
                                        + "' has manually_unread_for entry '"
                                        + username
                                        + "' which is not one of this conversation's participants");
                    }
                }
            }
            if (conversation.nicknames() != null) {
                for (String username : conversation.nicknames().keySet()) {
                    if (!participants.contains(username)) {
                        throw new IllegalStateException(
                                "conversations.json: conversation '"
                                        + conversation.id()
                                        + "' has a nicknames entry keyed by '"
                                        + username
                                        + "' which is not one of this conversation's participants");
                    }
                }
            }
        }
    }

    // Mirrors ModerationSeedWriter's own (reporter, report_type, entity) uniqueness guard, but
    // fails the load instead of silently dropping the second row at write time. targetUsername
    // stands in for the resolved entity here because every report_type this file authors resolves
    // deterministically from the target username alone (e.g. "post" resolves to that author's
    // first post), so two entries sharing (reporter, report_type, target_username) always resolve
    // to the same database row.
    private void validateSupplementaryReportsAreUnique(
            List<ModerationSupplementaryReport> supplementaryReports) {
        Set<String> seenKeys = new HashSet<>();
        for (ModerationSupplementaryReport report : supplementaryReports) {
            String key =
                    report.reporter() + ":" + report.reportType() + ":" + report.targetUsername();
            if (!seenKeys.add(key)) {
                throw new IllegalStateException(
                        "moderation_cases.json: supplementary_reports has more than one entry with"
                                + " reporter '"
                                + report.reporter()
                                + "', report_type '"
                                + report.reportType()
                                + "', target_username '"
                                + report.targetUsername()
                                + "' - these resolve to the same (reporter, report_type, entity) row"
                                + " and the second one would be silently dropped at write time");
            }
        }
    }

    private CommentPoolSeed readCommentPools() {
        return readFile("content/comment_pools.json", CommentPoolSeed.class);
    }

    private List<MediaManifestEntry> readMediaManifest() {
        MediaManifestFile file = readFile("media/media_manifest.json", MediaManifestFile.class);
        List<MediaManifestEntry> all = new ArrayList<>();
        Stream.of(file.images(), file.videos(), file.avatars(), file.banners())
                .filter(Objects::nonNull)
                .forEach(all::addAll);
        return all;
    }

    private <F, T> List<T> readList(
            String fileName, Class<F> fileType, Function<F, List<T>> extractor) {
        F file = readFile(fileName, fileType);
        return extractor.apply(file);
    }

    private <T> T readFile(String fileName, Class<T> type) {
        Resource resource = new ClassPathResource(classpathBase + "/" + fileName);
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, type);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read or parse seed file '"
                            + fileName
                            + "' from classpath:"
                            + classpathBase,
                    e);
        }
    }

    private record PersonasFile(List<PersonaSeed> personas) {}

    private record UsersFile(List<UserSeed> users) {}

    private record HashtagsFile(List<HashtagSeed> hashtags) {}

    private record PostsFile(List<PostSeed> posts) {}

    private record ConversationsFile(List<ConversationSeed> conversations) {}

    private record ModerationCasesFile(
            List<ModerationCaseSeed> cases,
            List<ModerationSupplementaryAction> supplementaryActions,
            List<ModerationSupplementaryReport> supplementaryReports) {}

    private record MediaManifestFile(
            List<MediaManifestEntry> images,
            List<MediaManifestEntry> videos,
            List<MediaManifestEntry> avatars,
            List<MediaManifestEntry> banners) {}
}
