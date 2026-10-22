package com.app.modules.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
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
    private static final List<UUID> NO_EXCLUDED_AUTHORS = List.of(new UUID(0L, 0L));

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
    void userPosts_tieGroupOnPublishedAt_pagesEveryRowExactlyOnce() {
        UUID author = insertUser("tie_author");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertPost(author, SHARED_INSTANT));
        }

        List<UUID> seen = new ArrayList<>();
        List<Post> page = postRepository.findFirstUserPosts(author, PAGE_SIZE);
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(p -> seen.add(p.getId()));
            Post last = page.get(page.size() - 1);
            page =
                    postRepository.findUserPostsBefore(
                            author, last.getPublishedAt(), last.getId(), PAGE_SIZE);
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void feed_tieGroupOnPublishedAt_pagesEveryRowExactlyOnce() {
        UUID viewer = insertUser("feed_viewer");
        UUID author = insertUser("feed_author");
        List<UUID> authorIds = List.of(author);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertPost(author, SHARED_INSTANT));
        }

        assertThat(pageFeed(authorIds, viewer)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void feed_notInterestedPostsInsideATieGroup_areSkippedWithoutLosingOrRepeatingTheRest() {
        UUID viewer = insertUser("feed_ni_viewer");
        UUID author = insertUser("feed_ni_author");
        List<UUID> authorIds = List.of(author);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID post = insertPost(author, SHARED_INSTANT);
            if (i % 2 == 0) {
                markNotInterested(viewer, post);
            } else {
                expected.add(post);
            }
        }

        assertThat(pageFeed(authorIds, viewer)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void feed_notInterestedMarkOfAnotherViewer_doesNotHideThePost() {
        UUID viewer = insertUser("feed_other_viewer");
        UUID other = insertUser("feed_other_marker");
        UUID author = insertUser("feed_other_author");
        UUID post = insertPost(author, SHARED_INSTANT);
        markNotInterested(other, post);

        assertThat(pageFeed(List.of(author), viewer)).containsExactly(post);
    }

    // The plan is read with sequential scans disabled so a small test table cannot hide the access
    // path the production table would take. The statement is read from the repository's own @Query
    // annotation rather than repeated here, so a change to either feed query is checked by this
    // test without anyone remembering to copy it across. The methods are looked up by their
    // parameter types because getDeclaredMethods() order is unspecified.
    @ParameterizedTest(name = "{0} with {1} author(s)")
    @MethodSource("feedQueryShapes")
    void feed_keysetQuery_stillUsesTheKeysetIndexAndAntiJoinsTheMarks(
            String method, int authorCount, boolean withCursor) throws NoSuchMethodException {
        String shape = (withCursor ? "before" : "first") + authorCount;
        UUID viewer = insertUser("plan_viewer_" + shape);
        List<UUID> authors = new ArrayList<>();
        for (int a = 0; a < authorCount; a++) {
            UUID author = insertUser("plan_author_" + shape + "_" + a);
            authors.add(author);
            for (int i = 0; i < 20; i++) {
                insertPost(author, SHARED_INSTANT.minusMinutes(i));
            }
        }
        jdbcClient.sql("SET LOCAL enable_seqscan = off").update();

        StatementSpec spec =
                jdbcClient
                        .sql("EXPLAIN " + feedQuery(method, withCursor))
                        .param("limit", 21)
                        .param("authorIds", authors)
                        .param("excludedAuthorIds", NO_EXCLUDED_AUTHORS)
                        .param("viewerId", viewer);
        if (withCursor) {
            spec = spec.param("cursorTime", SHARED_INSTANT).param("cursorId", UUID.randomUUID());
        }
        String plan = String.join("\n", spec.query(String.class).list());

        if (authorCount == 1) {
            assertThat(plan)
                    .as("the keyset index must drive the scan")
                    .contains("idx_posts_user_published_id");
        } else {
            // With several authors the planner may order by another posts index and filter the
            // authors; what matters is that the table is never read sequentially.
            assertThat(plan).as("no sequential scan of posts").doesNotContain("Seq Scan");
        }
        assertThat(plan).as("the marks are excluded by an anti-join").contains("Anti Join");
        assertThat(plan)
                .as("the marks are probed through their primary key")
                .contains("post_not_interested_pkey");
    }

    private static Stream<Arguments> feedQueryShapes() {
        return Stream.of(
                Arguments.of("findFirstFeedPosts", 1, false),
                Arguments.of("findFirstFeedPosts", 3, false),
                Arguments.of("findFeedPostsBefore", 1, true),
                Arguments.of("findFeedPostsBefore", 3, true));
    }

    private static String feedQuery(String method, boolean withCursor)
            throws NoSuchMethodException {
        Method repositoryMethod =
                withCursor
                        ? PostRepository.class.getMethod(
                                method,
                                List.class,
                                List.class,
                                UUID.class,
                                OffsetDateTime.class,
                                UUID.class,
                                int.class)
                        : PostRepository.class.getMethod(
                                method, List.class, List.class, UUID.class, int.class);
        return repositoryMethod.getAnnotation(Query.class).value();
    }

    private List<UUID> pageFeed(List<UUID> authorIds, UUID viewer) {
        List<UUID> seen = new ArrayList<>();
        List<Post> page =
                postRepository.findFirstFeedPosts(
                        authorIds, NO_EXCLUDED_AUTHORS, viewer, PAGE_SIZE);
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(p -> seen.add(p.getId()));
            Post last = page.get(page.size() - 1);
            page =
                    postRepository.findFeedPostsBefore(
                            authorIds,
                            NO_EXCLUDED_AUTHORS,
                            viewer,
                            last.getPublishedAt(),
                            last.getId(),
                            PAGE_SIZE);
        }
        return seen;
    }

    private void markNotInterested(UUID viewer, UUID post) {
        jdbcClient
                .sql("INSERT INTO post_not_interested(user_id, post_id) VALUES (:viewer, :post)")
                .param("viewer", viewer)
                .param("post", post)
                .update();
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
                        "INSERT INTO posts(user_id, post_type, status, created_at, published_at)"
                                + " VALUES (:userId, 'image', 'published', :createdAt, :createdAt)"
                                + " RETURNING id")
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
