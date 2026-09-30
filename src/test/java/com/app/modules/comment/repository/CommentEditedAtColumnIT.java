package com.app.modules.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * Guards the {@code comments.edited_at} column added by V45 at the schema level.
 *
 * <p>Runs the full Flyway migration set against an empty database, so a comment inserted without
 * the column stands in for every row written before the migration: the value must be null, which is
 * the honest answer for a row whose edit history was never recorded. Also checks that nothing in
 * the schema writes the column on its own, which is the property separating it from {@code
 * updated_at}.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommentEditedAtColumnIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final CommentRepository commentRepository;
    private final JdbcClient jdbcClient;
    private final EntityManager entityManager;

    CommentEditedAtColumnIT(
            CommentRepository commentRepository,
            JdbcClient jdbcClient,
            EntityManager entityManager) {
        this.commentRepository = commentRepository;
        this.jdbcClient = jdbcClient;
        this.entityManager = entityManager;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void migration_rowInsertedWithoutTheColumn_hasNullEditedAt() {
        UUID postId = seedPost();
        UUID commentId = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO comments (id, post_id, user_id, content) VALUES (:id, :postId,"
                                + " :userId, 'written before the migration')")
                .param("id", commentId)
                .param("postId", postId)
                .param("userId", ownerOf(postId))
                .update();

        assertThat(editedAt(commentId)).isNull();
    }

    @Test
    void insertThroughTheRepository_leavesEditedAtNull() {
        UUID postId = seedPost();
        Comment saved =
                commentRepository.save(
                        Comment.builder()
                                .postId(postId)
                                .userId(ownerOf(postId))
                                .depth((short) 0)
                                .content("never edited")
                                .moderationStatus("approved")
                                .build());
        entityManager.flush();

        assertThat(saved.getEditedAt()).isNull();
        assertThat(editedAt(saved.getId())).isNull();
    }

    @Test
    void counterUpdate_movesUpdatedAtAndLeavesEditedAtNull() {
        UUID postId = seedPost();
        Comment saved =
                commentRepository.save(
                        Comment.builder()
                                .postId(postId)
                                .userId(ownerOf(postId))
                                .depth((short) 0)
                                .content("liked, not edited")
                                .moderationStatus("approved")
                                .build());
        entityManager.flush();

        // Straight through the like trigger rather than the service, so this asserts the schema
        // behaviour rather than the application's use of it.
        jdbcClient
                .sql(
                        "INSERT INTO comment_likes (user_id, comment_id) VALUES (:userId,"
                                + " :commentId)")
                .param("userId", ownerOf(postId))
                .param("commentId", saved.getId())
                .update();

        // The counter moved, so trg_comments_updated_at fired on this row, and edited_at survived
        // it untouched. That updated_at advances in wall-clock terms is asserted over HTTP in
        // CommentControllerIT instead: this slice runs in one transaction, and NOW() is the
        // transaction's start time, so the trigger writes the value the insert already had.
        assertThat(likeCount(saved.getId())).isEqualTo(1);
        assertThat(updatedAt(saved.getId())).isNotNull();
        assertThat(editedAt(saved.getId())).isNull();
    }

    @Test
    void editedAtWrittenByApplicationCode_persists() {
        UUID postId = seedPost();
        Comment saved =
                commentRepository.save(
                        Comment.builder()
                                .postId(postId)
                                .userId(ownerOf(postId))
                                .depth((short) 0)
                                .content("about to change")
                                .moderationStatus("approved")
                                .build());
        entityManager.flush();

        OffsetDateTime stamp = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        saved.setContent("changed");
        saved.setEditedAt(stamp);
        commentRepository.save(saved);
        entityManager.flush();

        assertThat(editedAt(saved.getId())).isNotNull().isEqualTo(stamp);
    }

    private UUID seedPost() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        jdbcClient
                .sql("INSERT INTO users (id, username, email) VALUES (:id, :username, :email)")
                .param("id", userId)
                .param("username", "ea_" + suffix)
                .param("email", "ea_" + suffix + "@test.local")
                .update();
        UUID postId = UUID.randomUUID();
        jdbcClient
                .sql("INSERT INTO posts (id, user_id, caption) VALUES (:id, :userId, 'edited-at')")
                .param("id", postId)
                .param("userId", userId)
                .update();
        return postId;
    }

    private UUID ownerOf(UUID postId) {
        return jdbcClient
                .sql("SELECT user_id FROM posts WHERE id = :id")
                .param("id", postId)
                .query(UUID.class)
                .single();
    }

    private OffsetDateTime editedAt(UUID commentId) {
        return jdbcClient
                .sql("SELECT edited_at FROM comments WHERE id = :id")
                .param("id", commentId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
    }

    private int likeCount(UUID commentId) {
        return jdbcClient
                .sql("SELECT like_count FROM comments WHERE id = :id")
                .param("id", commentId)
                .query(Integer.class)
                .single();
    }

    private OffsetDateTime updatedAt(UUID commentId) {
        return jdbcClient
                .sql("SELECT updated_at FROM comments WHERE id = :id")
                .param("id", commentId)
                .query(OffsetDateTime.class)
                .single();
    }
}
