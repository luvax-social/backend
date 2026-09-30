package com.app.modules.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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

import com.app.testsupport.TestContainerImages;

/**
 * Differential guard over {@link CommentRepository#softDeleteSubtree}: the statement is bounded to
 * the target's own thread through {@code root_id} rather than walking {@code parent_id} across the
 * whole table, and this asserts the bound changes only the plan, never the set of rows affected.
 *
 * <p>Each shape computes the unbounded walk's selection first, then runs the real statement and
 * compares the rows it actually soft-deleted against it. The unbounded walk is kept here, and only
 * here, as the reference definition of the correct answer; it must not survive in {@code src/main}.
 *
 * <p>The shape that matters most is a live comment beneath a soft-deleted one, which {@code
 * AdminServiceImpl.moderateComment} produces by restoring a single row. Neither walk filters {@code
 * deleted_at} during the recursion, so both reach the live descendant through the deleted parent; a
 * replacement that pruned there would strand it alive and orphaned.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommentSubtreeDeleteEquivalenceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    /**
     * Distinct from any pre-existing soft delete, so the rows this call touched are identifiable.
     */
    private static final OffsetDateTime DELETION_INSTANT =
            OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime EARLIER_DELETION =
            OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    private final CommentRepository commentRepository;
    private final JdbcClient jdbcClient;

    CommentSubtreeDeleteEquivalenceIT(CommentRepository commentRepository, JdbcClient jdbcClient) {
        this.commentRepository = commentRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void singleNode_selectsTheSameRowsAsTheUnboundedWalk() {
        Fixture fixture = new Fixture();
        UUID target = fixture.topLevel();
        fixture.decoyThread();

        assertSelectsTheSameRowsAsTheUnboundedWalk(target);
    }

    @Test
    void deepChain_selectsTheSameRowsAsTheUnboundedWalk() {
        Fixture fixture = new Fixture();
        UUID target = fixture.topLevel();
        UUID parent = target;
        // The depth column is capped at 10 by a CHECK constraint, so this is the deepest legal
        // chain and therefore the most recursion levels the walk can ever perform.
        for (int depth = 1; depth <= 10; depth++) {
            parent = fixture.reply(parent, target, depth);
        }
        fixture.decoyThread();

        assertSelectsTheSameRowsAsTheUnboundedWalk(target);
    }

    @Test
    void wideFanOut_selectsTheSameRowsAsTheUnboundedWalk() {
        Fixture fixture = new Fixture();
        UUID target = fixture.topLevel();
        for (int i = 0; i < 25; i++) {
            fixture.reply(target, target, 1);
        }
        fixture.decoyThread();

        assertSelectsTheSameRowsAsTheUnboundedWalk(target);
    }

    @Test
    void targetIsItselfAReply_selectsTheSameRowsAsTheUnboundedWalk() {
        Fixture fixture = new Fixture();
        UUID root = fixture.topLevel();
        UUID target = fixture.reply(root, root, 1);
        UUID child = fixture.reply(target, root, 2);
        fixture.reply(child, root, 3);
        // A sibling of the target inside the same thread: it shares the bound but is not in the
        // subtree, so it must survive.
        fixture.reply(root, root, 1);
        fixture.decoyThread();

        assertSelectsTheSameRowsAsTheUnboundedWalk(target);
    }

    @Test
    void liveDescendantBeneathASoftDeletedParent_selectsTheSameRowsAsTheUnboundedWalk() {
        Fixture fixture = new Fixture();
        UUID target = fixture.topLevel();
        UUID deletedMiddle = fixture.reply(target, target, 1);
        fixture.reply(deletedMiddle, target, 2);
        softDelete(deletedMiddle);
        fixture.decoyThread();

        List<UUID> selected = assertSelectsTheSameRowsAsTheUnboundedWalk(target);

        // Explicit rather than implied by the equivalence: the already-deleted middle is skipped
        // by the final predicate while the walk still passes through it to reach the live leaf.
        assertThat(selected).hasSize(2).doesNotContain(deletedMiddle);
    }

    @Test
    void siblingThreadOnTheSamePost_isNeverTouched() {
        Fixture fixture = new Fixture();
        UUID target = fixture.topLevel();
        fixture.reply(target, target, 1);
        UUID sibling = fixture.topLevel();
        UUID siblingReply = fixture.reply(sibling, sibling, 1);

        commentRepository.softDeleteSubtree(target, DELETION_INSTANT);

        assertThat(deletedAtOf(sibling)).isNull();
        assertThat(deletedAtOf(siblingReply)).isNull();
    }

    /**
     * Runs the unbounded walk, then the real statement, and asserts they agree on both the row set
     * and the count. Also asserts {@code countSubtree} predicted that count, since the delete path
     * reports it to the caller and the two must not drift apart.
     *
     * @param target root of the subtree to soft-delete
     * @return the ids the statement soft-deleted
     */
    private List<UUID> assertSelectsTheSameRowsAsTheUnboundedWalk(UUID target) {
        List<UUID> expected = unboundedWalkSelection(target);
        int predicted = commentRepository.countSubtree(target);

        int reported = commentRepository.softDeleteSubtree(target, DELETION_INSTANT);
        List<UUID> actual = idsSoftDeletedAt(DELETION_INSTANT);

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(reported).isEqualTo(expected.size());
        assertThat(predicted).isEqualTo(expected.size());
        return actual;
    }

    /**
     * The statement as it stood before the thread bound, kept as the reference answer. Walks {@code
     * parent_id} from the target with no bound and no {@code deleted_at} filter during recursion,
     * applying it only at the end exactly where the real statement applies it.
     */
    private List<UUID> unboundedWalkSelection(UUID commentId) {
        return jdbcClient
                .sql(
                        """
						WITH RECURSIVE subtree AS (
							SELECT id FROM comments WHERE id = :commentId
							UNION ALL
							SELECT c.id FROM comments c
							JOIN subtree s ON c.parent_id = s.id
						)
						SELECT id FROM comments
						WHERE id IN (SELECT id FROM subtree) AND deleted_at IS NULL
						""")
                .param("commentId", commentId)
                .query(UUID.class)
                .list();
    }

    private List<UUID> idsSoftDeletedAt(OffsetDateTime deletedAt) {
        return jdbcClient
                .sql("SELECT id FROM comments WHERE deleted_at = :deletedAt")
                .param("deletedAt", deletedAt)
                .query(UUID.class)
                .list();
    }

    private OffsetDateTime deletedAtOf(UUID commentId) {
        return jdbcClient
                .sql("SELECT deleted_at FROM comments WHERE id = :id")
                .param("id", commentId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
    }

    private void softDelete(UUID commentId) {
        jdbcClient
                .sql("UPDATE comments SET deleted_at = :deletedAt WHERE id = :id")
                .param("deletedAt", EARLIER_DELETION)
                .param("id", commentId)
                .update();
    }

    /** One post with one author, against which every shape in a test is built. */
    private final class Fixture {

        private final UUID authorId = insertUser();
        private final UUID postId = insertPost(authorId);

        UUID topLevel() {
            return insertComment(postId, authorId, null, null, 0);
        }

        UUID reply(UUID parentId, UUID rootId, int depth) {
            return insertComment(postId, authorId, parentId, rootId, depth);
        }

        /**
         * A second thread on the same post that no assertion expects to be affected. Its purpose is
         * to give the thread bound something it must exclude, so a bound that silently widened to
         * the whole post would be caught.
         */
        void decoyThread() {
            UUID decoyRoot = topLevel();
            UUID decoyChild = reply(decoyRoot, decoyRoot, 1);
            reply(decoyChild, decoyRoot, 2);
        }
    }

    private UUID insertUser() {
        String username = "sde_" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcClient
                .sql(
                        "INSERT INTO users(username, email, display_name)"
                                + " VALUES (:username, :email, :username) RETURNING id")
                .param("username", username)
                .param("email", username + "@example.com")
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

    private UUID insertComment(UUID postId, UUID userId, UUID parentId, UUID rootId, int depth) {
        return jdbcClient
                .sql(
                        "INSERT INTO comments(post_id, user_id, parent_id, root_id, depth, content,"
                                + " moderation_status) VALUES (:postId, :userId, :parentId,"
                                + " :rootId, :depth, 'text', 'approved') RETURNING id")
                .param("postId", postId)
                .param("userId", userId)
                .param("parentId", parentId)
                .param("rootId", rootId)
                .param("depth", depth)
                .query(UUID.class)
                .single();
    }
}
