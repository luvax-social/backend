package com.app.modules.comment.repository;

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

import com.app.modules.comment.entity.Comment;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when approved comments or replies share a boundary {@code
 * created_at}. Pages with a size that cuts into the tie-group and asserts every row is returned
 * exactly once; a dropped row proves a strict-inequality regression, a duplicated row a non-strict
 * one.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommentKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final CommentRepository commentRepository;
    private final JdbcClient jdbcClient;

    CommentKeysetRowLossIT(CommentRepository commentRepository, JdbcClient jdbcClient) {
        this.commentRepository = commentRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    private static final UUID[] NOTHING_EXCLUDED = new UUID[0];

    @Test
    void topLevelComments_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID user = insertUser("commenter");
        UUID postId = insertPost(user);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertTopLevelComment(postId, user, SHARED_INSTANT));
        }

        // Empty exclusion array: the shape the newest mode uses, and the shape the top mode
        // reduces to on a post whose comments carry no likes.
        List<UUID> seen = pageTopLevel(postId, user, NOTHING_EXCLUDED);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void topLevelComments_pinnedExcluded_pagesEveryRemainingRowExactlyOnce() {
        UUID user = insertUser("pinned_commenter");
        UUID postId = insertPost(user);
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(insertTopLevelComment(postId, user, SHARED_INSTANT));
        }
        // Two of the five stand in for a pinned block; the body must return the other three
        // exactly once each and must never return the excluded pair.
        UUID[] excluded = {all.get(0), all.get(1)};

        List<UUID> seen = pageTopLevel(postId, user, excluded);

        assertThat(seen).containsExactlyInAnyOrder(all.get(2), all.get(3), all.get(4));
        assertThat(seen).doesNotContain(excluded);
    }

    private List<UUID> pageTopLevel(UUID postId, UUID viewerId, UUID[] excludedIds) {
        List<UUID> seen = new ArrayList<>();
        List<Comment> page =
                commentRepository.findFirstTopLevelExcluding(postId, excludedIds, viewerId, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(c -> seen.add(c.getId()));
            Comment last = page.get(page.size() - 1);
            page =
                    commentRepository.findTopLevelBefore(
                            postId,
                            excludedIds,
                            viewerId,
                            last.getCreatedAt(),
                            last.getId(),
                            page());
        }
        return seen;
    }

    @Test
    void replies_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID user = insertUser("replier");
        UUID postId = insertPost(user);
        UUID parentId = insertTopLevelComment(postId, user, SHARED_INSTANT.minusHours(1));
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertReply(postId, user, parentId, SHARED_INSTANT));
        }

        List<UUID> seen = new ArrayList<>();
        List<Comment> page = commentRepository.findFirstReplies(parentId, user, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(c -> seen.add(c.getId()));
            Comment last = page.get(page.size() - 1);
            page =
                    commentRepository.findRepliesBefore(
                            parentId, user, last.getCreatedAt(), last.getId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void topLevelComments_authorsHiddenRowsInTieGroup_arePagedExactlyOnceForTheAuthorOnly() {
        UUID owner = insertUser("hidden_page_owner");
        UUID author = insertUser("hidden_page_author");
        UUID other = insertUser("hidden_page_other");
        UUID postId = insertPost(owner);
        List<UUID> authorSees = new ArrayList<>();
        List<UUID> othersSee = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID approved = insertTopLevelComment(postId, other, SHARED_INSTANT);
            UUID hidden = insertHiddenTopLevelComment(postId, author, SHARED_INSTANT);
            authorSees.add(approved);
            authorSees.add(hidden);
            othersSee.add(approved);
        }

        assertThat(pageTopLevel(postId, author, NOTHING_EXCLUDED))
                .containsExactlyInAnyOrderElementsOf(authorSees);
        assertThat(pageTopLevel(postId, other, NOTHING_EXCLUDED))
                .containsExactlyInAnyOrderElementsOf(othersSee);
        assertThat(pageTopLevel(postId, null, NOTHING_EXCLUDED))
                .containsExactlyInAnyOrderElementsOf(othersSee);
    }

    @Test
    void replies_authorsHiddenRowsInTieGroup_arePagedExactlyOnceForTheAuthorOnly() {
        UUID owner = insertUser("hidden_reply_owner");
        UUID author = insertUser("hidden_reply_author");
        UUID other = insertUser("hidden_reply_other");
        UUID postId = insertPost(owner);
        UUID parentId = insertTopLevelComment(postId, owner, SHARED_INSTANT.minusHours(1));
        List<UUID> authorSees = new ArrayList<>();
        List<UUID> othersSee = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID approved = insertReply(postId, other, parentId, SHARED_INSTANT);
            UUID hidden = insertHiddenReply(postId, author, parentId, SHARED_INSTANT);
            authorSees.add(approved);
            authorSees.add(hidden);
            othersSee.add(approved);
        }

        assertThat(pageReplies(parentId, author)).containsExactlyInAnyOrderElementsOf(authorSees);
        assertThat(pageReplies(parentId, other)).containsExactlyInAnyOrderElementsOf(othersSee);
        assertThat(pageReplies(parentId, null)).containsExactlyInAnyOrderElementsOf(othersSee);
    }

    @Test
    void firstTopLevelPage_withTheViewerOrPredicate_stillUsesTheKeysetIndex() {
        UUID owner = insertUser("explain_owner");
        UUID author = insertUser("explain_author");
        UUID target = insertPost(owner);
        // Enough rows across enough posts that a sequential scan is not the cheapest plan, with a
        // share of hidden rows so the OR branch has something to match.
        jdbcClient
                .sql(
                        """
						INSERT INTO posts(user_id, post_type, status)
						SELECT :owner, 'image', 'published' FROM generate_series(1, 40)
						""")
                .param("owner", owner)
                .update();
        jdbcClient
                .sql(
                        """
						INSERT INTO comments(post_id, user_id, depth, content, moderation_status,
											hidden_reason, created_at)
						SELECT p.id, :author, 0, 'text',
							CASE WHEN g % 5 = 0 THEN 'hidden' ELSE 'approved' END,
							CASE WHEN g % 5 = 0 THEN 'hidden_words' END,
							:base + (g || ' seconds')::interval
						FROM (SELECT id FROM posts) p, generate_series(1, 120) g
						""")
                .param("author", author)
                .param("base", SHARED_INSTANT)
                .update();
        jdbcClient.sql("ANALYZE comments").update();

        // Mirrors the predicate and ordering of CommentRepository.findFirstTopLevelExcluding; the
        // statement is restated because a repository query cannot be EXPLAINed through Spring Data.
        String plan =
                String.join(
                        "\n",
                        jdbcClient
                                .sql(
                                        """
										EXPLAIN SELECT * FROM comments
										WHERE post_id = :postId AND parent_id IS NULL
										AND (moderation_status = 'approved'
											OR (moderation_status = 'hidden' AND user_id = :viewerId))
										AND deleted_at IS NULL AND admin_removed_at IS NULL
										AND id <> ALL(CAST('{}' AS uuid[]))
										AND NOT EXISTS (SELECT 1 FROM blocks b
											WHERE (b.blocker_id = :viewerId
												AND b.blocked_id = comments.user_id)
											OR (b.blocker_id = comments.user_id
												AND b.blocked_id = :viewerId))
										ORDER BY created_at DESC, id DESC
										LIMIT 21
										""")
                                .param("postId", target)
                                .param("viewerId", author)
                                .query(String.class)
                                .list());

        assertThat(plan).contains("idx_comments_post_root_id");
    }

    private List<UUID> pageReplies(UUID parentId, UUID viewerId) {
        List<UUID> seen = new ArrayList<>();
        List<Comment> page = commentRepository.findFirstReplies(parentId, viewerId, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(c -> seen.add(c.getId()));
            Comment last = page.get(page.size() - 1);
            page =
                    commentRepository.findRepliesBefore(
                            parentId, viewerId, last.getCreatedAt(), last.getId(), page());
        }
        return seen;
    }

    private UUID insertHiddenTopLevelComment(UUID postId, UUID userId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO comments(post_id, user_id, depth, content, moderation_status,"
                                + " hidden_reason, created_at) VALUES (:postId, :userId, 0, 'text',"
                                + " 'hidden', 'hidden_words', :createdAt) RETURNING id")
                .param("postId", postId)
                .param("userId", userId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }

    private UUID insertHiddenReply(
            UUID postId, UUID userId, UUID parentId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO comments(post_id, user_id, parent_id, root_id, depth, content,"
                                + " moderation_status, hidden_reason, created_at) VALUES (:postId,"
                                + " :userId, :parentId, :parentId, 1, 'text', 'hidden',"
                                + " 'hidden_words', :createdAt) RETURNING id")
                .param("postId", postId)
                .param("userId", userId)
                .param("parentId", parentId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
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

    private UUID insertPost(UUID userId) {
        return jdbcClient
                .sql(
                        "INSERT INTO posts(user_id, post_type, status)"
                                + " VALUES (:userId, 'image', 'published') RETURNING id")
                .param("userId", userId)
                .query(UUID.class)
                .single();
    }

    private UUID insertTopLevelComment(UUID postId, UUID userId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO comments(post_id, user_id, depth, content, moderation_status,"
                                + " created_at) VALUES (:postId, :userId, 0, 'text', 'approved',"
                                + " :createdAt) RETURNING id")
                .param("postId", postId)
                .param("userId", userId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }

    private UUID insertReply(UUID postId, UUID userId, UUID parentId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO comments(post_id, user_id, parent_id, root_id, depth, content,"
                                + " moderation_status, created_at) VALUES (:postId, :userId, :parentId,"
                                + " :parentId, 1, 'text', 'approved', :createdAt) RETURNING id")
                .param("postId", postId)
                .param("userId", userId)
                .param("parentId", parentId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }
}
