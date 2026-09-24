package com.app.modules.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

import com.app.modules.notification.entity.enums.NotificationFilter;
import com.app.modules.notification.repository.NotificationFeedRepository.FeedRow;
import com.app.modules.notification.repository.NotificationFeedRepository.Key;

/**
 * The feed reads against PostgreSQL: the visibility predicate every read shares, the bounded unseen
 * count, the head, keyset pages per filter across equal sort keys, the displayed actors, and the
 * index each filter uses.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@Import(NotificationFeedRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class NotificationFeedRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final OffsetDateTime SHARED =
            OffsetDateTime.of(2026, 5, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private final NotificationFeedRepository repository;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    private UUID viewer;
    private UUID active;
    private UUID other;
    private UUID banned;
    private UUID blockedByViewer;
    private UUID blockingViewer;

    NotificationFeedRepositoryIT(
            NotificationFeedRepository repository,
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
        viewer = user("active");
        active = user("active");
        other = user("active");
        banned = user("banned");
        blockedByViewer = user("active");
        blockingViewer = user("active");
        block(viewer, blockedByViewer);
        block(blockingViewer, viewer);
    }

    @Test
    void visibility_hidesRowsWhoseOnlyActorIsBlockedOrInactive_andDeletedRows() {
        UUID visible = row("comment_post", "comment", SHARED.minusMinutes(1), active);
        row("comment_post", "comment", SHARED.minusMinutes(2), blockedByViewer);
        row("comment_post", "comment", SHARED.minusMinutes(3), blockingViewer);
        row("comment_post", "comment", SHARED.minusMinutes(4), banned);
        UUID deleted = row("comment_post", "comment", SHARED.minusMinutes(5), active);
        jdbc.sql("UPDATE notifications SET deleted_at = now() WHERE id = :id")
                .param("id", deleted)
                .update();
        UUID system = row("warning", "system", SHARED.minusMinutes(6));

        assertThat(ids(repository.findPage(viewer, NotificationFilter.ALL, null, 50)))
                .containsExactly(visible, system);
        assertThat(repository.countUnseen(viewer, null)).isEqualTo(2);
        assertThat(repository.findVisible(viewer, deleted)).isEmpty();
    }

    @Test
    void visibility_groupWithOneBlockedMember_staysVisibleAndCountsTheHiddenMember() {
        UUID group = row("like_post", "like", SHARED, active, blockedByViewer, other);

        assertThat(repository.findVisible(viewer, group)).isPresent();
        assertThat(repository.findDisplayActors(viewer, List.of(group)).get(group))
                .containsExactlyInAnyOrder(active, other);
        assertThat(repository.countHiddenActors(viewer, List.of(group))).containsEntry(group, 1);
    }

    @Test
    void findDisplayActors_returnsAtMostThreeNewestFirst() {
        UUID[] actors = {user("active"), user("active"), user("active"), user("active")};
        UUID group = row("like_post", "like", SHARED, actors);

        List<UUID> shown = repository.findDisplayActors(viewer, List.of(group)).get(group);

        assertThat(shown).containsExactly(actors[3], actors[2], actors[1]);
    }

    @Test
    void countUnseen_isBoundedAndCountsOnlyRowsAboveTheWatermark() {
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            UUID id = row("warning", "system", SHARED.plusSeconds(i));
            keys.add(new Key(SHARED.plusSeconds(i), id));
        }

        assertThat(repository.countUnseen(viewer, null)).isEqualTo(100);
        assertThat(repository.countUnseen(viewer, keys.get(103))).isEqualTo(1);
        assertThat(repository.countUnseen(viewer, keys.get(104))).isZero();
    }

    @Test
    void pendingRequests_countAndLeadTheHead_butAreNeverListRows() {
        UUID comment = row("comment_post", "comment", SHARED, active);
        UUID request = row("follow_request", "follow", SHARED.plusMinutes(1), other);

        assertThat(repository.findHead(viewer)).contains(new Key(SHARED.plusMinutes(1), request));
        assertThat(repository.countUnseen(viewer, new Key(SHARED, comment))).isEqualTo(1);
        for (NotificationFilter filter : NotificationFilter.values()) {
            assertThat(ids(repository.findPage(viewer, filter, null, 50))).doesNotContain(request);
        }
        assertThat(repository.findVisible(viewer, request)).isPresent();
    }

    @Test
    void findPage_everyFilterPaginatesAcrossEqualSortKeysWithoutLossOrDuplicates() {
        Map<NotificationFilter, Set<UUID>> expected = new EnumMap<>(NotificationFilter.class);
        for (NotificationFilter filter : NotificationFilter.values()) {
            expected.put(filter, new HashSet<>());
        }
        for (int i = 0; i < 5; i++) {
            track(expected, row("comment_post", "comment", SHARED, active), "comment", false);
            track(expected, row("mention_comment", "mention", SHARED, active), "mention", false);
            track(expected, row("follow", "follow", SHARED, active), "follow", false);
            track(expected, row("warning", "system", SHARED), "system", false);
            UUID verified = row("like_post", "like", SHARED, other);
            jdbc.sql("UPDATE notifications SET actor_verified = TRUE WHERE id = :id")
                    .param("id", verified)
                    .update();
            track(expected, verified, "like", true);
        }
        UUID read = row("comment_post", "comment", SHARED, active);
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE id = :id")
                .param("id", read)
                .update();
        track(expected, read, "comment", false);
        expected.get(NotificationFilter.UNREAD).remove(read);

        for (NotificationFilter filter : NotificationFilter.values()) {
            List<UUID> seen = new ArrayList<>();
            Key before = null;
            while (true) {
                List<FeedRow> page = repository.findPage(viewer, filter, before, 2);
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(row -> seen.add(row.id()));
                FeedRow last = page.get(page.size() - 1);
                before = new Key(last.activityAt(), last.id());
            }
            assertThat(seen).as(filter.name()).doesNotHaveDuplicates();
            assertThat(seen)
                    .as(filter.name())
                    .containsExactlyInAnyOrderElementsOf(expected.get(filter));
        }
    }

    @Test
    void findPage_eachFilterIsServedByItsOwnIndex() {
        for (int i = 0; i < 20; i++) {
            row("comment_post", "comment", SHARED.plusSeconds(i), active);
        }
        jdbc.sql("ANALYZE notifications").update();
        Map<NotificationFilter, String> index = new EnumMap<>(NotificationFilter.class);
        index.put(NotificationFilter.ALL, "idx_notifications_feed");
        index.put(NotificationFilter.UNREAD, "idx_notifications_feed_unread");
        index.put(NotificationFilter.COMMENTS, "idx_notifications_feed_category");
        index.put(NotificationFilter.SYSTEM, "idx_notifications_feed_category");
        index.put(NotificationFilter.VERIFIED, "idx_notifications_feed_verified");

        index.forEach(
                (filter, expectedIndex) -> {
                    String plan =
                            tx.execute(
                                    status -> {
                                        jdbc.sql("SET LOCAL enable_seqscan = off").update();
                                        jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
                                        return String.join(
                                                "\n",
                                                jdbc.sql(
                                                                "EXPLAIN SELECT n.id FROM"
                                                                        + " notifications n WHERE "
                                                                        + NotificationFeedRepository
                                                                                .VISIBLE
                                                                        + " AND n.type <>"
                                                                        + " 'follow_request' AND "
                                                                        + filter.predicate()
                                                                        + " ORDER BY n.activity_at"
                                                                        + " DESC, n.id DESC LIMIT"
                                                                        + " 21")
                                                        .param("viewer", viewer)
                                                        .query(String.class)
                                                        .list());
                                    });
                    assertThat(plan)
                            .as(filter.name())
                            .contains(expectedIndex + " on notifications");
                });
    }

    private void track(
            Map<NotificationFilter, Set<UUID>> expected,
            UUID id,
            String category,
            boolean verified) {
        expected.get(NotificationFilter.ALL).add(id);
        expected.get(NotificationFilter.UNREAD).add(id);
        switch (category) {
            case "comment" -> expected.get(NotificationFilter.COMMENTS).add(id);
            case "mention" -> expected.get(NotificationFilter.MENTIONS).add(id);
            case "follow" -> expected.get(NotificationFilter.FOLLOWS).add(id);
            case "system" -> expected.get(NotificationFilter.SYSTEM).add(id);
            default -> {}
        }
        if (verified) {
            expected.get(NotificationFilter.VERIFIED).add(id);
        }
    }

    private static List<UUID> ids(List<FeedRow> rows) {
        return rows.stream().map(FeedRow::id).toList();
    }

    private UUID row(String type, String category, OffsetDateTime activityAt, UUID... actors) {
        UUID id =
                jdbc.sql(
                                "INSERT INTO notifications (recipient_id, actor_id, type, category,"
                                        + " activity_at) VALUES (:viewer, :actor,"
                                        + " CAST(:type AS notification_type),"
                                        + " CAST(:category AS notification_category), :activityAt)"
                                        + " RETURNING id")
                        .param("viewer", viewer)
                        .param("actor", actors.length == 0 ? null : actors[actors.length - 1])
                        .param("type", type)
                        .param("category", category)
                        .param("activityAt", activityAt)
                        .query(UUID.class)
                        .single();
        for (int i = 0; i < actors.length; i++) {
            jdbc.sql(
                            "INSERT INTO notification_actors (notification_id, actor_id, acted_at)"
                                    + " VALUES (:id, :actor, :actedAt)")
                    .param("id", id)
                    .param("actor", actors[i])
                    .param("actedAt", activityAt.minusSeconds(actors.length - i))
                    .update();
        }
        return id;
    }

    private void block(UUID blocker, UUID blocked) {
        jdbc.sql("INSERT INTO blocks (blocker_id, blocked_id) VALUES (:blocker, :blocked)")
                .param("blocker", blocker)
                .param("blocked", blocked)
                .update();
    }

    private UUID user(String status) {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.sql(
                        "INSERT INTO users (username, email, status) VALUES (:name, :email,"
                                + " CAST(:status AS user_status)) RETURNING id")
                .param("name", name)
                .param("email", name + "@example.com")
                .param("status", status)
                .query(UUID.class)
                .single();
    }
}
