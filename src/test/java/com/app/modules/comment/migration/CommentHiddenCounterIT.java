package com.app.modules.comment.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;

/**
 * Pins the comment counter triggers as V143 redefined them: a comment counts toward {@code
 * posts.comment_count} and its parent's {@code reply_count} only while it is approved and carries
 * neither tombstone, and the hidden reason is present exactly when the comment is hidden.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommentHiddenCounterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final JdbcClient jdbcClient;

    CommentHiddenCounterIT(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void hiddenComment_isNotCounted_andUnhideCountsIt() {
        UUID postId = seedPost();
        UUID author = seedUser();
        insertComment(postId, author, null, "approved", null);
        assertThat(commentCount(postId)).isEqualTo(1);

        UUID hidden = insertComment(postId, author, null, "hidden", "hidden_words");
        assertThat(commentCount(postId)).isEqualTo(1);

        setStatus(hidden, "approved", null);
        assertThat(commentCount(postId)).isEqualTo(2);

        softDelete(hidden);
        assertThat(commentCount(postId)).isEqualTo(1);

        UUID hiddenAgain = insertComment(postId, author, null, "hidden", "limited_interaction");
        softDelete(hiddenAgain);
        assertThat(commentCount(postId)).isEqualTo(1);
    }

    @Test
    void hiddenReply_isNotCountedOnItsParent_andUnhideCountsIt() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);

        UUID visibleReply = insertComment(postId, author, parent, "approved", null);
        assertThat(replyCount(parent)).isEqualTo(1);

        UUID hiddenReply = insertComment(postId, author, parent, "hidden", "hidden_words");
        assertThat(replyCount(parent)).isEqualTo(1);
        assertThat(commentCount(postId)).isEqualTo(2);

        setStatus(hiddenReply, "approved", null);
        assertThat(replyCount(parent)).isEqualTo(2);

        setStatus(hiddenReply, "hidden", "limited_interaction");
        assertThat(replyCount(parent)).isEqualTo(1);
        assertThat(commentCount(postId)).isEqualTo(2);

        softDelete(hiddenReply);
        assertThat(replyCount(parent)).isEqualTo(1);
        softDelete(visibleReply);
        assertThat(replyCount(parent)).isZero();
    }

    @Test
    void restoredApprovedComment_isCountedAgain_onBothCounters() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "approved", null);
        softDelete(reply);
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(replyCount(parent)).isZero();

        restore(reply);

        assertThat(commentCount(postId)).isEqualTo(2);
        assertThat(replyCount(parent)).isEqualTo(1);
    }

    @Test
    void restoredHiddenComment_isNotCounted_onEitherCounter() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "hidden", "hidden_words");
        softDelete(reply);

        restore(reply);

        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(replyCount(parent)).isZero();
    }

    @Test
    void hardDeletedApprovedComment_isUncounted_onBothCounters() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "approved", null);
        assertThat(commentCount(postId)).isEqualTo(2);

        hardDelete(reply);

        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(replyCount(parent)).isZero();
    }

    @Test
    void hardDeletedHiddenComment_leavesBothCountersUnchanged() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        insertComment(postId, author, parent, "approved", null);
        UUID hiddenReply = insertComment(postId, author, parent, "hidden", "hidden_words");
        assertThat(commentCount(postId)).isEqualTo(2);
        assertThat(replyCount(parent)).isEqualTo(1);

        hardDelete(hiddenReply);

        assertThat(commentCount(postId)).isEqualTo(2);
        assertThat(replyCount(parent)).isEqualTo(1);
    }

    @Test
    void adminRemovedApprovedReply_isNotCountedOnItsParent_andRestoreCountsIt() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "approved", null);

        adminRemove(reply);
        assertThat(replyCount(parent)).isZero();
        assertThat(commentCount(postId)).isEqualTo(1);

        adminRestore(reply);
        assertThat(replyCount(parent)).isEqualTo(1);
        assertThat(commentCount(postId)).isEqualTo(2);
    }

    @Test
    void adminRemoveAndRestoreOfAHiddenComment_neverCountsIt() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "hidden", "limited_interaction");

        adminRemove(reply);
        assertThat(replyCount(parent)).isZero();
        assertThat(commentCount(postId)).isEqualTo(1);

        adminRestore(reply);
        assertThat(replyCount(parent)).isZero();
        assertThat(commentCount(postId)).isEqualTo(1);
    }

    @Test
    void unhidingASoftDeletedComment_doesNotChangeEitherCounter() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "hidden", "hidden_words");
        softDelete(reply);

        setStatus(reply, "approved", null);

        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(replyCount(parent)).isZero();
    }

    @Test
    void unhidingAnAdminRemovedComment_doesNotChangeEitherCounter() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID parent = insertComment(postId, author, null, "approved", null);
        UUID reply = insertComment(postId, author, parent, "hidden", "hidden_words");
        adminRemove(reply);

        setStatus(reply, "approved", null);

        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(replyCount(parent)).isZero();
    }

    @Test
    void adminRemovedComment_isNotCounted_andRestoreCountsIt() {
        UUID postId = seedPost();
        UUID author = seedUser();
        UUID approved = insertComment(postId, author, null, "approved", null);
        assertThat(commentCount(postId)).isEqualTo(1);

        jdbcClient
                .sql("UPDATE comments SET admin_removed_at = NOW() WHERE id = :id")
                .param("id", approved)
                .update();
        assertThat(commentCount(postId)).isZero();

        jdbcClient
                .sql("UPDATE comments SET admin_removed_at = NULL WHERE id = :id")
                .param("id", approved)
                .update();
        assertThat(commentCount(postId)).isEqualTo(1);
    }

    @Test
    void hiddenComment_withoutAReason_isRejected() {
        UUID postId = seedPost();
        UUID author = seedUser();

        assertThatThrownBy(() -> insertComment(postId, author, null, "hidden", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvedComment_withAReason_isRejected() {
        UUID postId = seedPost();
        UUID author = seedUser();

        assertThatThrownBy(() -> insertComment(postId, author, null, "approved", "hidden_words"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID seedUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        jdbcClient
                .sql("INSERT INTO users (id, username, email) VALUES (:id, :username, :email)")
                .param("id", userId)
                .param("username", "hc_" + suffix)
                .param("email", "hc_" + suffix + "@test.local")
                .update();
        return userId;
    }

    private UUID seedPost() {
        UUID owner = seedUser();
        UUID postId = UUID.randomUUID();
        jdbcClient
                .sql("INSERT INTO posts (id, user_id, caption) VALUES (:id, :userId, 'hidden')")
                .param("id", postId)
                .param("userId", owner)
                .update();
        return postId;
    }

    private UUID insertComment(
            UUID postId, UUID userId, UUID parentId, String status, String hiddenReason) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO comments (id, post_id, user_id, parent_id, root_id, depth,"
                                + " content, moderation_status, hidden_reason) VALUES (:id,"
                                + " :postId, :userId, :parentId, :parentId, :depth, 'text',"
                                + " :status, :reason)")
                .param("id", id)
                .param("postId", postId)
                .param("userId", userId)
                .param("parentId", parentId)
                .param("depth", parentId == null ? 0 : 1)
                .param("status", status)
                .param("reason", hiddenReason)
                .update();
        return id;
    }

    private void setStatus(UUID commentId, String status, String hiddenReason) {
        jdbcClient
                .sql(
                        "UPDATE comments SET moderation_status = :status, hidden_reason = :reason"
                                + " WHERE id = :id")
                .param("id", commentId)
                .param("status", status)
                .param("reason", hiddenReason)
                .update();
    }

    private void softDelete(UUID commentId) {
        jdbcClient
                .sql("UPDATE comments SET deleted_at = NOW() WHERE id = :id")
                .param("id", commentId)
                .update();
    }

    private void restore(UUID commentId) {
        jdbcClient
                .sql("UPDATE comments SET deleted_at = NULL WHERE id = :id")
                .param("id", commentId)
                .update();
    }

    private void hardDelete(UUID commentId) {
        jdbcClient.sql("DELETE FROM comments WHERE id = :id").param("id", commentId).update();
    }

    private void adminRemove(UUID commentId) {
        jdbcClient
                .sql("UPDATE comments SET admin_removed_at = NOW() WHERE id = :id")
                .param("id", commentId)
                .update();
    }

    private void adminRestore(UUID commentId) {
        jdbcClient
                .sql("UPDATE comments SET admin_removed_at = NULL WHERE id = :id")
                .param("id", commentId)
                .update();
    }

    private int commentCount(UUID postId) {
        return jdbcClient
                .sql("SELECT comment_count FROM posts WHERE id = :id")
                .param("id", postId)
                .query(Integer.class)
                .single();
    }

    private int replyCount(UUID commentId) {
        return jdbcClient
                .sql("SELECT reply_count FROM comments WHERE id = :id")
                .param("id", commentId)
                .query(Integer.class)
                .single();
    }
}
