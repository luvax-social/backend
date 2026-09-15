package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;

/**
 * Loads the real seed JSON files under {@code src/main/resources/seed/} to prove Task 1/2's content
 * is internally consistent by this loader's rules. Not a fixture test — a failure here means the
 * actual seed data is broken, not the loader.
 */
class SeedDataLoaderRealDataTest {

    @Test
    void load_realSeedContent_loadsSuccessfully() {
        SeedContent content = new SeedDataLoader().load();

        assertThat(content.personas()).hasSize(10);
        assertThat(content.users()).hasSize(90);
        assertThat(content.hashtags()).hasSize(152);
        assertThat(content.posts()).hasSize(722);
        assertThat(content.conversations()).hasSize(85);
        assertThat(content.moderationCases()).hasSize(6);
        assertThat(content.supplementaryModerationActions()).hasSize(153);
        assertThat(content.supplementaryModerationReports()).hasSize(27);
        // 125 images (120 original + 5 reclassified from the former avatar pool - posts.json
        // still references them, see media_manifest.json's _reclassified_avatar_pool_note), 15
        // videos, 25 banners. Avatars are no longer in this file at all - see users.json's
        // avatar_url field and UserSeedWriter.
        assertThat(content.mediaManifest()).hasSize(125 + 15 + 25);
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
        assertThat(content.supportTicketPools().verificationRequests()).isNotEmpty();
    }
}
