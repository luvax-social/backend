package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;
import com.app.common.seed.model.InteractionControlSeed;

/**
 * Loads the real seed JSON files under {@code src/main/resources/seed/} to prove the seed content
 * is internally consistent by this loader's rules. Not a fixture test — a failure here means the
 * actual seed data is broken, not the loader.
 */
class SeedDataLoaderRealDataTest {

    @Test
    void load_realSeedContent_loadsSuccessfully() {
        SeedContent content = new SeedDataLoader().load();

        assertThat(content.personas()).hasSize(14);
        assertThat(content.users()).hasSize(140);
        assertThat(content.hashtags()).hasSize(164);
        assertThat(content.posts()).hasSize(746);
        assertThat(content.conversations()).hasSize(85);
        assertThat(content.moderationCases()).hasSize(6);
        assertThat(content.supplementaryModerationActions()).hasSize(168);
        assertThat(content.supplementaryModerationReports()).hasSize(27);
        // 536 images (120 original, 5 reclassified from the former avatar pool - posts.json
        // still references them, see media_manifest.json's _reclassified_avatar_pool_note - and
        // 411 appended), 70 videos, 25 banners. Avatars are no longer in this file at all - see
        // users.json's avatar_url field and UserSeedWriter.
        assertThat(content.mediaManifest()).hasSize(536 + 70 + 25);
    }

    @Test
    void load_realSeedContent_populatesBadges() {
        SeedContent content = new SeedDataLoader().load();

        assertThat(content.badges()).isNotNull();
        assertThat(content.badges().verificationTickets()).hasSize(16);
        assertThat(content.badges().legacyGrants()).hasSize(12);
        assertThat(content.badges().revocations()).hasSize(5);

        // Five tickets are still in the queue, and V107 admits one per account, so they must sit
        // on five distinct accounts.
        assertThat(
                        content.badges().verificationTickets().stream()
                                .filter(t -> !t.isTerminal())
                                .map(t -> t.username())
                                .distinct())
                .hasSize(5);
    }

    @Test
    void load_realSeedContent_coversEveryInteractionControlShapeAtLeastFiveTimes() {
        SeedContent content = new SeedDataLoader().load();
        InteractionControlSeed seed = content.interactionControl();

        assertThat(count(seed.mutes(), m -> !m.undone() && m.mutePosts() && !m.muteStories()))
                .as("posts-only mutes")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.mutes(), m -> !m.undone() && !m.mutePosts() && m.muteStories()))
                .as("stories-only mutes")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.mutes(), m -> !m.undone() && m.mutePosts() && m.muteStories()))
                .as("mutes of both")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.mutes(), m -> m.undone()))
                .as("undone mutes")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.showFewer(), s -> !s.undone()))
                .as("show fewer")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.showFewer(), s -> s.undone()))
                .as("undone show fewer")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.notInterested(), n -> !n.undone()))
                .as("not interested")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.notInterested(), n -> n.undone()))
                .as("undone not interested")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.interactionLimits(), l -> l.startedDaysAgo() < l.durationDays()))
                .as("active limits")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.interactionLimits(), l -> l.startedDaysAgo() >= l.durationDays()))
                .as("expired limits")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.hiddenWords(), w -> w.owner() != null))
                .as("personal words")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.hiddenWords(), w -> w.owner() == null && !w.deleted()))
                .as("platform words")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.hiddenWords(), w -> w.previousPhrase() != null))
                .as("edited platform words")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.hiddenWords(), w -> w.deleted()))
                .as("deleted platform words")
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        count(
                                seed.hiddenComments(),
                                c -> !c.unhidden() && "hidden_words".equals(c.reason())))
                .as("comments hidden by words")
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        count(
                                seed.hiddenComments(),
                                c -> !c.unhidden() && "limited_interaction".equals(c.reason())))
                .as("comments hidden by a limit")
                .isGreaterThanOrEqualTo(5);
        assertThat(count(seed.hiddenComments(), c -> c.unhidden()))
                .as("unhidden comments")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void load_realSeedContent_spreadsMessagePermissionAcrossAllThreeValues() {
        SeedContent content = new SeedDataLoader().load();

        for (String permission : List.of("everyone", "following", "nobody")) {
            assertThat(count(content.users(), u -> permission.equals(u.messagePermission())))
                    .as("accounts with message_permission %s", permission)
                    .isGreaterThanOrEqualTo(5);
        }
        assertThat(count(content.users(), u -> !u.hiddenWordsSystemEnabled()))
                .as("accounts that switched the platform word list off")
                .isGreaterThanOrEqualTo(5);
    }

    private static <T> long count(List<T> items, Predicate<T> predicate) {
        return items.stream().filter(predicate).count();
    }

    @Test
    void load_realSeedContent_populatesSupportTicketPools() {
        SeedContent content = new SeedDataLoader().load();

        assertThat(content.supportTicketPools()).isNotNull();
        assertThat(content.supportTicketPools().requestsByCategory())
                .containsKeys(
                        "appeal_ban",
                        "appeal_suspension",
                        "appeal_warning_strike",
                        "appeal_content_removal",
                        "account_access",
                        "account_data",
                        "bug_report",
                        "safety_concern",
                        "other");
    }
}
