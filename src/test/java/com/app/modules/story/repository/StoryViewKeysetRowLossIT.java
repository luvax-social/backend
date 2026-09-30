package com.app.modules.story.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.story.entity.StoryView;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when story views share a boundary {@code viewed_at}. Pages with a
 * size that cuts into the tie-group and asserts every viewer is returned exactly once; a dropped
 * row proves a strict-inequality regression, a duplicated row a non-strict one.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class StoryViewKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final StoryViewRepository storyViewRepository;
    private final JdbcClient jdbcClient;

    StoryViewKeysetRowLossIT(StoryViewRepository storyViewRepository, JdbcClient jdbcClient) {
        this.storyViewRepository = storyViewRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void storyViewers_tieGroupOnViewedAt_pagesEveryRowExactlyOnce() {
        UUID owner = insertUser("story_owner");
        UUID storyId = insertStory(owner, insertMediaAsset(owner));
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID viewer = insertUser("viewer_" + i);
            insertStoryView(storyId, viewer, SHARED_INSTANT);
            expected.add(viewer);
        }

        List<UUID> seen = new ArrayList<>();
        List<StoryView> page = storyViewRepository.findFirstViewers(storyId, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(v -> seen.add(v.getId().getViewerId()));
            StoryView last = page.get(page.size() - 1);
            page =
                    storyViewRepository.findViewersBefore(
                            storyId, last.getViewedAt(), last.getId().getViewerId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static PageRequest page() {
        return PageRequest.of(0, PAGE_SIZE);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :displayName)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertMediaAsset(UUID userId) {
        return jdbcClient
                .sql(
                        "INSERT INTO media_assets(user_id, storage_key, cdn_url, media_type,"
                                + " mime_type, file_size) VALUES (:userId, :storageKey, 'https://cdn/x',"
                                + " 'image', 'image/jpeg', 1000) RETURNING id")
                .param("userId", userId)
                .param("storageKey", UUID.randomUUID().toString())
                .query(UUID.class)
                .single();
    }

    private UUID insertStory(UUID userId, UUID mediaAssetId) {
        return jdbcClient
                .sql(
                        "INSERT INTO stories(user_id, media_asset_id, story_type)"
                                + " VALUES (:userId, :mediaAssetId, 'image') RETURNING id")
                .param("userId", userId)
                .param("mediaAssetId", mediaAssetId)
                .query(UUID.class)
                .single();
    }

    private void insertStoryView(UUID storyId, UUID viewerId, OffsetDateTime viewedAt) {
        jdbcClient
                .sql(
                        "INSERT INTO story_views(story_id, viewer_id, viewed_at)"
                                + " VALUES (:storyId, :viewerId, :viewedAt)")
                .param("storyId", storyId)
                .param("viewerId", viewerId)
                .param("viewedAt", viewedAt)
                .update();
    }
}
