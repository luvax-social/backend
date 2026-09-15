package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;

class SeedDataLoaderTest {

    @Test
    void load_validFixture_loadsSuccessfully() {
        SeedContent content = new SeedDataLoader("seed-fixtures/valid").load();

        assertThat(content.personas()).hasSize(1);
        assertThat(content.users()).hasSize(2);
        assertThat(content.hashtags()).hasSize(1);
        assertThat(content.posts()).hasSize(1);
        assertThat(content.commentPools().pools()).containsKey("software-engineering");
        assertThat(content.conversations()).hasSize(1);
        assertThat(content.moderationCases()).hasSize(1);
        assertThat(content.mediaManifest()).hasSize(2);
        assertThat(content.supportTicketPools().requestsByCategory()).containsKey("bug_report");
    }

    @Test
    void load_postAuthorNotInUsers_throwsIllegalStateException() {
        SeedDataLoader loader = new SeedDataLoader("seed-fixtures/broken-author");

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("author_username")
                .hasMessageContaining("nonexistent_user");
    }

    @Test
    void load_postMediaRefNotInManifest_throwsIllegalStateException() {
        SeedDataLoader loader = new SeedDataLoader("seed-fixtures/broken-media");

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media_refs")
                .hasMessageContaining("missing_media_id");
    }

    @Test
    void load_postTopicHasNoCommentPool_throwsIllegalStateException() {
        SeedDataLoader loader = new SeedDataLoader("seed-fixtures/broken-topic");

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic_tags")
                .hasMessageContaining("nonexistent-topic");
    }

    @Test
    void load_conversationParticipantNotInUsers_throwsIllegalStateException() {
        SeedDataLoader loader = new SeedDataLoader("seed-fixtures/broken-participant");

        assertThatThrownBy(loader::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("participant")
                .hasMessageContaining("ghost_user");
    }
}
