package com.app.modules.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationAggregationRepository.GroupWrite;
import com.app.modules.notification.repository.NotificationAggregationRepository.Removal;
import com.app.modules.notification.service.NotificationDraft;

/**
 * Exercises the aggregation SQL against PostgreSQL: opening and joining a group, a repeated actor,
 * the lazy window close, retraction, concurrent first actors, request conversion, block cleanup and
 * the batched verified resync.
 *
 * <p>Not transactional: every write commits, as it does in production, so the concurrency case sees
 * two real transactions contend on the partial unique index.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@Import(NotificationAggregationRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class NotificationAggregationRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Duration WINDOW = Duration.ofHours(24);

    private final NotificationAggregationRepository repository;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    private UUID recipient;
    private UUID anna;
    private UUID ben;
    private UUID post;

    NotificationAggregationRepositoryIT(
            NotificationAggregationRepository repository,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach
    void setUp() {
        recipient = user(false);
        anna = user(false);
        ben = user(true);
        post = UUID.randomUUID();
    }

    @Test
    void upsertGroup_firstActor_opensAGroupWithOneMember() {
        GroupWrite write = like(anna);

        assertThat(write.opened()).isTrue();
        assertThat(write.joined()).isTrue();
        Map<String, Object> row = row(write.id());
        assertThat(row.get("actor_count")).isEqualTo(1);
        assertThat(row.get("is_group_open")).isEqualTo(true);
        assertThat(row.get("aggregation_key")).isEqualTo("like_post:" + post);
    }

    @Test
    void upsertGroup_secondActor_joinsMovesToTheTopAndBecomesUnread() {
        GroupWrite first = like(anna);
        markRead(first.id());
        OffsetDateTime before = activityAt(first.id());

        GroupWrite second =
                tx.execute(
                        status ->
                                repository.upsertGroup(
                                        draft(ben),
                                        NotificationCategory.LIKE,
                                        "like_post:" + post,
                                        WINDOW,
                                        true));
        tx.executeWithoutResult(status -> repository.touchActivity(second.id()));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.opened()).isFalse();
        assertThat(second.joined()).isTrue();
        Map<String, Object> row = row(first.id());
        assertThat(row.get("actor_count")).isEqualTo(2);
        assertThat(row.get("actor_id")).isEqualTo(ben);
        assertThat(row.get("actor_verified")).isEqualTo(true);
        assertThat(row.get("read_at")).isNull();
        assertThat(activityAt(first.id())).isAfter(before);
    }

    @Test
    void upsertGroup_actorAlreadyInTheGroup_changesNothing() {
        GroupWrite first = like(anna);
        markRead(first.id());

        GroupWrite again = like(anna);

        assertThat(again.joined()).isFalse();
        Map<String, Object> row = row(first.id());
        assertThat(row.get("actor_count")).isEqualTo(1);
        assertThat(row.get("read_at")).isNotNull();
    }

    @Test
    void upsertGroup_groupOlderThanTheWindow_closesItAndOpensANewOne() {
        GroupWrite old = like(anna);
        jdbc.sql(
                        "UPDATE notifications SET group_started_at = now() - interval '25 hours'"
                                + " WHERE id = :id")
                .param("id", old.id())
                .update();

        GroupWrite fresh = like(ben);

        assertThat(fresh.id()).isNotEqualTo(old.id());
        assertThat(fresh.opened()).isTrue();
        assertThat(row(old.id()).get("is_group_open")).isEqualTo(false);
        assertThat(row(old.id()).get("actor_count")).isEqualTo(1);
    }

    @Test
    void removeMemberFromGroups_leavesOthers_revertsTheActorWithoutResurfacing() {
        GroupWrite group = like(anna);
        like(ben);
        markRead(group.id());
        OffsetDateTime activity = activityAt(group.id());

        List<Removal> removals =
                tx.execute(
                        status ->
                                repository.removeMemberFromGroups(
                                        recipient, ben, "like_post:" + post));

        assertThat(removals).containsExactly(new Removal(group.id(), false, anna));
        Map<String, Object> row = row(group.id());
        assertThat(row.get("actor_count")).isEqualTo(1);
        assertThat(row.get("actor_id")).isEqualTo(anna);
        assertThat(row.get("read_at")).isNotNull();
        assertThat(activityAt(group.id())).isEqualTo(activity);
    }

    @Test
    void removeMemberFromGroups_lastActor_softDeletesAndClosesTheGroup() {
        GroupWrite group = like(anna);

        List<Removal> removals =
                tx.execute(
                        status ->
                                repository.removeMemberFromGroups(
                                        recipient, anna, "like_post:" + post));

        assertThat(removals).containsExactly(new Removal(group.id(), true, anna));
        Map<String, Object> row = row(group.id());
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("is_group_open")).isEqualTo(false);
        assertThat(like(ben).opened()).isTrue();
    }

    @Test
    void upsertGroup_concurrentFirstActors_landInOneGroup() throws Exception {
        UUID carl = user(false);
        List<UUID> actors = List.of(anna, ben, carl);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actors.size());
        try {
            List<Future<GroupWrite>> writes = new ArrayList<>();
            for (UUID actor : actors) {
                writes.add(
                        pool.submit(
                                () -> {
                                    start.await(10, TimeUnit.SECONDS);
                                    return like(actor);
                                }));
            }
            start.countDown();
            for (Future<GroupWrite> write : writes) {
                write.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<UUID> groups =
                jdbc.sql(
                                "SELECT id FROM notifications WHERE recipient_id = :recipient"
                                        + " AND aggregation_key = :key AND deleted_at IS NULL")
                        .param("recipient", recipient)
                        .param("key", "like_post:" + post)
                        .query(UUID.class)
                        .list();
        assertThat(groups).hasSize(1);
        assertThat(row(groups.get(0)).get("actor_count")).isEqualTo(3);
    }

    @Test
    void convertRequestToFollow_keepsPositionAndReadState() {
        UUID request = single(anna, NotificationType.FOLLOW_REQUEST);
        markRead(request);
        OffsetDateTime activity = activityAt(request);

        Optional<UUID> found =
                tx.execute(status -> repository.findLiveFollowRequest(recipient, anna));
        Boolean converted = tx.execute(status -> repository.convertRequestToFollow(request));
        Boolean convertedAgain = tx.execute(status -> repository.convertRequestToFollow(request));

        assertThat(found).contains(request);
        assertThat(converted).isTrue();
        assertThat(convertedAgain).isFalse();
        Map<String, Object> row = row(request);
        assertThat(row.get("type")).isEqualTo("follow");
        assertThat(row.get("read_at")).isNotNull();
        assertThat(activityAt(request)).isEqualTo(activity);
    }

    @Test
    void removeMemberFromFollowRows_withdrawsAPendingRequest() {
        UUID request = single(anna, NotificationType.FOLLOW_REQUEST);

        List<Removal> removals =
                tx.execute(status -> repository.removeMemberFromFollowRows(recipient, anna));

        assertThat(removals).containsExactly(new Removal(request, true, anna));
        assertThat(row(request).get("deleted_at")).isNotNull();
    }

    @Test
    void removeMemberOnBlock_leavesNonAggregatedRowsForTheReadTimeFilter() {
        GroupWrite group = like(anna);
        UUID comment = single(anna, NotificationType.COMMENT_POST);

        tx.execute(status -> repository.removeMemberOnBlock(recipient, anna));

        assertThat(row(group.id()).get("deleted_at")).isNotNull();
        assertThat(row(comment).get("deleted_at")).isNull();
        assertThat(row(comment).get("actor_count")).isEqualTo(1);
    }

    @Test
    void resyncActorVerified_rewritesOnlyRowsWhoseNewestActorChanged_inBoundedBatches() {
        for (int i = 0; i < 3; i++) {
            post = UUID.randomUUID();
            like(anna);
        }
        UUID annasComment = single(anna, NotificationType.COMMENT_POST);
        post = UUID.randomUUID();
        GroupWrite shared = like(anna);
        like(ben);

        int first = tx.execute(status -> repository.resyncActorVerified(anna, true, 2));
        int second = tx.execute(status -> repository.resyncActorVerified(anna, true, 2));
        int third = tx.execute(status -> repository.resyncActorVerified(anna, true, 2));

        assertThat(first + second + third).isEqualTo(4);
        assertThat(third).isZero();
        assertThat(row(annasComment).get("actor_verified")).isEqualTo(true);
        // Ben is the newest actor of the shared group, so Anna's badge does not decide its flag.
        assertThat(row(shared.id()).get("actor_verified")).isEqualTo(true);
        assertThat(row(shared.id()).get("actor_id")).isEqualTo(ben);
    }

    private GroupWrite like(UUID actor) {
        return tx.execute(
                status ->
                        repository.upsertGroup(
                                draft(actor),
                                NotificationCategory.LIKE,
                                "like_post:" + post,
                                WINDOW,
                                actor.equals(ben)));
    }

    private UUID single(UUID actor, NotificationType type) {
        return tx.execute(
                status -> {
                    UUID id =
                            repository.insertSingle(
                                    NotificationDraft.of(actor, recipient, type, null, null, null),
                                    type.category(),
                                    false);
                    repository.addMember(id, actor);
                    return id;
                });
    }

    private NotificationDraft draft(UUID actor) {
        return NotificationDraft.of(
                actor, recipient, NotificationType.LIKE_POST, "post", post, post);
    }

    private void markRead(UUID id) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    private OffsetDateTime activityAt(UUID id) {
        return jdbc.sql("SELECT activity_at FROM notifications WHERE id = :id")
                .param("id", id)
                .query(OffsetDateTime.class)
                .single();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.sql(
                        "SELECT actor_id, type::text AS type, read_at, deleted_at, aggregation_key,"
                                + " is_group_open, actor_count, actor_verified FROM notifications"
                                + " WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }

    private UUID user(boolean verified) {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.sql(
                        "INSERT INTO users (username, email, is_verified) VALUES (:name, :email,"
                                + " :verified) RETURNING id")
                .param("name", name)
                .param("email", name + "@example.com")
                .param("verified", verified)
                .query(UUID.class)
                .single();
    }
}
