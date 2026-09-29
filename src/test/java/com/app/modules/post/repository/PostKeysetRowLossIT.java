package com.app.modules.post.repository;

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

import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostEditHistory;
import com.app.modules.post.entity.PostLike;
import com.app.modules.post.entity.PostSave;
import com.app.testsupport.TestContainerImages;

/**
 * Reproduces and guards against keyset row loss when rows share a boundary sort timestamp. A cursor
 * over the timestamp alone excludes an entire tie-group once a page boundary falls inside it; the
 * fix orders and compares on the {@code (timestamp, id)} tuple. Each test pages with a size that
 * cuts into the tie-group and asserts every inserted row is returned exactly once - a dropped row
 * proves a strict-inequality regression, a duplicated row proves a non-strict one.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostSaveRepository postSaveRepository;
    private final PostEditHistoryRepository postEditHistoryRepository;
    private final JdbcClient jdbcClient;

    PostKeysetRowLossIT(
            PostRepository postRepository,
            PostLikeRepository postLikeRepository,
            PostSaveRepository postSaveRepository,
            PostEditHistoryRepository postEditHistoryRepository,
            JdbcClient jdbcClient) {
        this.postRepository = postRepository;
        this.postLikeRepository = postLikeRepository;
        this.postSaveRepository = postSaveRepository;
        this.postEditHistoryRepository = postEditHistoryRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void userPosts_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID author = insertUser("tie_author");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertPost(author, SHARED_INSTANT));
        }

        List<UUID> seen = new ArrayList<>();
        List<Post> page = postRepository.findFirstUserPosts(author, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(p -> seen.add(p.getId()));
            Post last = page.get(page.size() - 1);
            page =
                    postRepository.findUserPostsBefore(
                            author, last.getCreatedAt(), last.getId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void feed_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID author = insertUser("feed_author");
        List<UUID> authorIds = List.of(author);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertPost(author, SHARED_INSTANT));
        }

        List<UUID> seen = new ArrayList<>();
        List<Post> page = postRepository.findFirstFeedPosts(authorIds, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(p -> seen.add(p.getId()));
            Post last = page.get(page.size() - 1);
            page =
                    postRepository.findFeedPostsBefore(
                            authorIds, last.getCreatedAt(), last.getId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void likes_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID owner = insertUser("like_post_owner");
        UUID postId = insertPost(owner, SHARED_INSTANT);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID liker = insertUser("liker_" + i);
            insertLike(postId, liker, SHARED_INSTANT);
            expected.add(liker);
        }

        List<UUID> seen = new ArrayList<>();
        List<PostLike> page = postLikeRepository.findFirstLikers(postId, owner, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(l -> seen.add(l.getId().getUserId()));
            PostLike last = page.get(page.size() - 1);
            page =
                    postLikeRepository.findLikersBefore(
                            postId, owner, last.getCreatedAt(), last.getId().getUserId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void postLikes_userScopedKeysetIndex_coversTheFullOrderingTuple() {
        // The user-scoped like listing orders by (created_at DESC, post_id DESC). An index stopping
        // at created_at leaves the tiebreaker to a sort, which for a large tie-group degrades a
        // single page into a full scan of the group. post_saves already has the equivalent index.
        String definition =
                jdbcClient
                        .sql(
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE tablename = 'post_likes'"
                                        + " AND indexdef LIKE '%(user_id, created_at DESC, post_id"
                                        + " DESC)%'")
                        .query(String.class)
                        .optional()
                        .orElse(null);

        assertThat(definition)
                .as("post_likes needs the (user_id, created_at DESC, post_id DESC) keyset index")
                .isNotNull();
    }

    @Test
    void saves_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID saver = insertUser("saver");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID postId = insertPost(insertUser("save_author_" + i), SHARED_INSTANT);
            insertSave(saver, postId, SHARED_INSTANT);
            expected.add(postId);
        }

        List<UUID> seen = new ArrayList<>();
        List<PostSave> page = postSaveRepository.findFirstSaves(saver, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(s -> seen.add(s.getId().getPostId()));
            PostSave last = page.get(page.size() - 1);
            page =
                    postSaveRepository.findSavesBefore(
                            saver, last.getCreatedAt(), last.getId().getPostId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void history_tieGroupOnEditedAt_pagesEveryRowExactlyOnce() {
        UUID editor = insertUser("editor");
        UUID postId = insertPost(editor, SHARED_INSTANT);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertEditHistory(postId, editor, SHARED_INSTANT));
        }

        List<UUID> seen = new ArrayList<>();
        List<PostEditHistory> page = postEditHistoryRepository.findFirstByPost(postId, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(h -> seen.add(h.getId()));
            PostEditHistory last = page.get(page.size() - 1);
            page =
                    postEditHistoryRepository.findByPostBefore(
                            postId, last.getEditedAt(), last.getId(), page());
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

    private UUID insertPost(UUID userId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO posts(user_id, post_type, status, created_at)"
                                + " VALUES (:userId, 'image', 'published', :createdAt) RETURNING id")
                .param("userId", userId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }

    private void insertLike(UUID postId, UUID userId, OffsetDateTime createdAt) {
        jdbcClient
                .sql(
                        "INSERT INTO post_likes(post_id, user_id, created_at)"
                                + " VALUES (:postId, :userId, :createdAt)")
                .param("postId", postId)
                .param("userId", userId)
                .param("createdAt", createdAt)
                .update();
    }

    private void insertSave(UUID userId, UUID postId, OffsetDateTime createdAt) {
        jdbcClient
                .sql(
                        "INSERT INTO post_saves(user_id, post_id, created_at)"
                                + " VALUES (:userId, :postId, :createdAt)")
                .param("userId", userId)
                .param("postId", postId)
                .param("createdAt", createdAt)
                .update();
    }

    private UUID insertEditHistory(UUID postId, UUID editorId, OffsetDateTime editedAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO post_edit_history(post_id, editor_id, previous_caption,"
                                + " edited_at) VALUES (:postId, :editorId, 'old', :editedAt) RETURNING"
                                + " id")
                .param("postId", postId)
                .param("editorId", editorId)
                .param("editedAt", editedAt)
                .query(UUID.class)
                .single();
    }
}
