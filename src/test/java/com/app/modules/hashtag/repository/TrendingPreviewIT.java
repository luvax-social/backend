package com.app.modules.hashtag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The in-feed trending card shows one cover image per tag, and this query is where that cover is
 * chosen. It carries the whole privacy surface of the card: the thumbnail is rendered without any
 * per-post visibility pass, so anything this query returns is shown to everyone.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class TrendingPreviewIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private HashtagTrendingRepository hashtagTrendingRepository;
    @Autowired private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void findCoverUrls_tagHasPosts_returnsTheNewestPublishedPostsCover() {
        UUID author = insertUser("previewauthor", false);
        UUID hashtag = insertHashtag("previewanalogue");
        insertPostWithMedia(author, hashtag, "https://cdn.example/older.jpg", "2026-01-01");
        insertPostWithMedia(author, hashtag, "https://cdn.example/newest.jpg", "2026-02-01");

        assertThat(coversFor(hashtag)).isEqualTo("https://cdn.example/newest.jpg");
    }

    @Test
    void findCoverUrls_topPostHasNoMedia_keepsTheTagWithANullCover() {
        UUID author = insertUser("previewtextonly", false);
        UUID hashtag = insertHashtag("previewtextonly");
        insertPost(author, hashtag, "2026-01-01");

        Map<UUID, String> covers = coverMap(List.of(hashtag));

        assertThat(covers).containsKey(hashtag);
        assertThat(covers.get(hashtag)).isNull();
    }

    @Test
    void findCoverUrls_authorIsPrivate_neverExposesTheirMedia() {
        UUID author = insertUser("previewprivate", true);
        UUID hashtag = insertHashtag("previewprivate");
        insertPostWithMedia(author, hashtag, "https://cdn.example/private.jpg", "2026-01-01");

        assertThat(coversFor(hashtag)).isNull();
    }

    @Test
    void findCoverUrls_postWasRemovedByModeration_doesNotUseItsCover() {
        UUID author = insertUser("previewremoved", false);
        UUID hashtag = insertHashtag("previewremoved");
        UUID post =
                insertPostWithMedia(
                        author, hashtag, "https://cdn.example/removed.jpg", "2026-01-01");
        jdbcClient
                .sql(
                        "UPDATE posts SET status_before_moderation = status, status = 'removed'"
                                + " WHERE id = :id")
                .param("id", post)
                .update();

        assertThat(coversFor(hashtag)).isNull();
    }

    @Test
    void findCoverUrls_postWasSoftDeleted_doesNotUseItsCover() {
        UUID author = insertUser("previewdeleted", false);
        UUID hashtag = insertHashtag("previewdeleted");
        UUID post =
                insertPostWithMedia(
                        author, hashtag, "https://cdn.example/deleted.jpg", "2026-01-01");
        jdbcClient
                .sql("UPDATE posts SET deleted_at = NOW() WHERE id = :id")
                .param("id", post)
                .update();

        assertThat(coversFor(hashtag)).isNull();
    }

    @Test
    void findCoverUrls_severalTags_answersOneRowPerTag() {
        UUID author = insertUser("previewmulti", false);
        UUID one = insertHashtag("previewmultione");
        UUID two = insertHashtag("previewmultitwo");
        insertPostWithMedia(author, one, "https://cdn.example/one.jpg", "2026-01-01");

        Map<UUID, String> covers = coverMap(List.of(one, two));

        assertThat(covers).hasSize(2);
        assertThat(covers.get(one)).isEqualTo("https://cdn.example/one.jpg");
        assertThat(covers.get(two)).isNull();
    }

    private String coversFor(UUID hashtagId) {
        return coverMap(List.of(hashtagId)).get(hashtagId);
    }

    private Map<UUID, String> coverMap(List<UUID> ids) {
        Map<UUID, String> byId = new HashMap<>();
        for (Object[] row : hashtagTrendingRepository.findCoverUrls(ids)) {
            byId.put((UUID) row[0], (String) row[1]);
        }
        return byId;
    }

    private UUID insertUser(String username, boolean isPrivate) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO users (id, username, email, is_private) VALUES (:id, :username,"
                                + " :email, :isPrivate)")
                .param("id", id)
                .param("username", username)
                .param("email", username + "@example.com")
                .param("isPrivate", isPrivate)
                .update();
        return id;
    }

    private UUID insertHashtag(String name) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql("INSERT INTO hashtags (id, name) VALUES (:id, :name)")
                .param("id", id)
                .param("name", name)
                .update();
        return id;
    }

    private UUID insertPost(UUID author, UUID hashtag, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO posts (id, user_id, created_at) VALUES (:id, :userId,"
                                + " CAST(:createdAt AS TIMESTAMPTZ))")
                .param("id", id)
                .param("userId", author)
                .param("createdAt", createdAt)
                .update();
        jdbcClient
                .sql("INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (:postId, :hashtagId)")
                .param("postId", id)
                .param("hashtagId", hashtag)
                .update();
        return id;
    }

    private UUID insertPostWithMedia(UUID author, UUID hashtag, String cdnUrl, String createdAt) {
        UUID post = insertPost(author, hashtag, createdAt);
        UUID media = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                                + " mime_type, file_size) VALUES (:id, :userId, :key, :cdnUrl,"
                                + " 'image', 'image/jpeg', 1024)")
                .param("id", media)
                .param("userId", author)
                .param("key", "key/" + media)
                .param("cdnUrl", cdnUrl)
                .update();
        jdbcClient
                .sql(
                        "INSERT INTO post_media (post_id, media_asset_id, \"position\") VALUES"
                                + " (:postId, :mediaId, 0)")
                .param("postId", post)
                .param("mediaId", media)
                .update();
        return post;
    }
}
