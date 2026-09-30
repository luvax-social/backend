package com.app.modules.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Exercises the two SQL statements behind the pinned top-comments block against real PostgreSQL:
 * the like-count ranking with its eligibility filter, and the newest-first body query with the
 * pinned ids excluded.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommentTopLikedQueryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PINNED = 3;

    private final CommentRepository commentRepository;
    private final JdbcClient jdbcClient;

    CommentTopLikedQueryIT(CommentRepository commentRepository, JdbcClient jdbcClient) {
        this.commentRepository = commentRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void findTopLikedTopLevel_ranksByLikeCountDescending() {
        UUID user = insertUser("ranker");
        UUID postId = insertPost(user);
        UUID low = insertTopLevelComment(postId, user, BASE, 1);
        UUID high = insertTopLevelComment(postId, user, BASE.minusMinutes(1), 50);
        UUID middle = insertTopLevelComment(postId, user, BASE.minusMinutes(2), 7);

        List<Comment> pinned = commentRepository.findTopLikedTopLevel(postId, user, pinnedPage());

        assertThat(pinned).extracting(Comment::getId).containsExactly(high, middle, low);
    }

    @Test
    void findTopLikedTopLevel_equalLikeCounts_breaksTieByCreatedAtThenIdDescending() {
        UUID user = insertUser("tied");
        UUID postId = insertPost(user);
        UUID older = insertTopLevelComment(postId, user, BASE.minusMinutes(5), 4);
        UUID newer = insertTopLevelComment(postId, user, BASE, 4);

        List<Comment> pinned = commentRepository.findTopLikedTopLevel(postId, user, pinnedPage());

        assertThat(pinned).extracting(Comment::getId).containsExactly(newer, older);
    }

    @Test
    void findTopLikedTopLevel_zeroLikeComment_isNotEligible() {
        UUID user = insertUser("zerolikes");
        UUID postId = insertPost(user);
        insertTopLevelComment(postId, user, BASE, 0);
        insertTopLevelComment(postId, user, BASE.minusMinutes(1), 0);

        List<Comment> pinned = commentRepository.findTopLikedTopLevel(postId, user, pinnedPage());

        assertThat(pinned).isEmpty();
    }

    @Test
    void findTopLikedTopLevel_ineligibleRows_areNeverPinnedDespiteHigherLikeCounts() {
        UUID user = insertUser("ineligible");
        UUID postId = insertPost(user);
        UUID onlyEligible = insertTopLevelComment(postId, user, BASE, 1);
        insertSoftDeletedTopLevelComment(postId, user, BASE.minusMinutes(1), 900);
        insertRemovedTopLevelComment(postId, user, BASE.minusMinutes(2), 950);
        insertReply(postId, user, onlyEligible, BASE.minusMinutes(3), 999);

        List<Comment> pinned = commentRepository.findTopLikedTopLevel(postId, user, pinnedPage());

        assertThat(pinned).extracting(Comment::getId).containsExactly(onlyEligible);
    }

    @Test
    void findTopLikedTopLevel_otherPostsComments_areNotPinned() {
        UUID user = insertUser("scoped");
        UUID postId = insertPost(user);
        UUID otherPostId = insertPost(user);
        UUID mine = insertTopLevelComment(postId, user, BASE, 2);
        insertTopLevelComment(otherPostId, user, BASE.minusMinutes(1), 900);

        List<Comment> pinned = commentRepository.findTopLikedTopLevel(postId, user, pinnedPage());

        assertThat(pinned).extracting(Comment::getId).containsExactly(mine);
    }

    @Test
    void findFirstTopLevelExcluding_excludedIds_areOmittedFromTheBody() {
        UUID user = insertUser("excluding");
        UUID postId = insertPost(user);
        UUID first = insertTopLevelComment(postId, user, BASE, 9);
        UUID second = insertTopLevelComment(postId, user, BASE.minusMinutes(1), 0);
        UUID third = insertTopLevelComment(postId, user, BASE.minusMinutes(2), 0);

        List<Comment> body =
                commentRepository.findFirstTopLevelExcluding(
                        postId, new UUID[] {first}, user, PageRequest.of(0, 10));

        assertThat(body).extracting(Comment::getId).containsExactly(second, third);
    }

    @Test
    void findFirstTopLevelExcluding_emptyExclusion_returnsEveryApprovedComment() {
        UUID user = insertUser("noexclusion");
        UUID postId = insertPost(user);
        UUID first = insertTopLevelComment(postId, user, BASE, 0);
        UUID second = insertTopLevelComment(postId, user, BASE.minusMinutes(1), 0);

        List<Comment> body =
                commentRepository.findFirstTopLevelExcluding(
                        postId, new UUID[0], user, PageRequest.of(0, 10));

        assertThat(body).extracting(Comment::getId).containsExactly(first, second);
    }

    @Test
    void findFirstTopLevelExcluding_limitCountsRowsAfterExclusion() {
        UUID user = insertUser("limitafter");
        UUID postId = insertPost(user);
        UUID excluded = insertTopLevelComment(postId, user, BASE, 9);
        UUID second = insertTopLevelComment(postId, user, BASE.minusMinutes(1), 0);
        UUID third = insertTopLevelComment(postId, user, BASE.minusMinutes(2), 0);

        List<Comment> body =
                commentRepository.findFirstTopLevelExcluding(
                        postId, new UUID[] {excluded}, user, PageRequest.of(0, 2));

        assertThat(body).extracting(Comment::getId).containsExactly(second, third);
    }

    private static PageRequest pinnedPage() {
        return PageRequest.of(0, PINNED);
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

    private UUID insertTopLevelComment(
            UUID postId, UUID userId, OffsetDateTime createdAt, int likeCount) {
        return insertComment(postId, userId, null, createdAt, likeCount, "approved", null);
    }

    private UUID insertSoftDeletedTopLevelComment(
            UUID postId, UUID userId, OffsetDateTime createdAt, int likeCount) {
        return insertComment(postId, userId, null, createdAt, likeCount, "approved", createdAt);
    }

    private UUID insertRemovedTopLevelComment(
            UUID postId, UUID userId, OffsetDateTime createdAt, int likeCount) {
        return insertComment(postId, userId, null, createdAt, likeCount, "removed", null);
    }

    private UUID insertReply(
            UUID postId, UUID userId, UUID parentId, OffsetDateTime createdAt, int likeCount) {
        return insertComment(postId, userId, parentId, createdAt, likeCount, "approved", null);
    }

    // like_count is trigger-maintained in production, so the harness writes it directly rather
    // than inserting the comment_likes rows the ranking is only a projection of.
    private UUID insertComment(
            UUID postId,
            UUID userId,
            UUID parentId,
            OffsetDateTime createdAt,
            int likeCount,
            String moderationStatus,
            OffsetDateTime deletedAt) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO comments(post_id, user_id, parent_id, root_id, depth, content,
											like_count, moderation_status, created_at, deleted_at)
						VALUES (:postId, :userId, :parentId, :parentId,
								CASE WHEN CAST(:parentId AS uuid) IS NULL THEN 0 ELSE 1 END,
								'text', :likeCount, :moderationStatus, :createdAt, :deletedAt)
						RETURNING id
						""")
                .param("postId", postId)
                .param("userId", userId)
                .param("parentId", parentId)
                .param("likeCount", likeCount)
                .param("moderationStatus", moderationStatus)
                .param("createdAt", createdAt)
                .param("deletedAt", deletedAt)
                .query(UUID.class)
                .single();
    }
}
