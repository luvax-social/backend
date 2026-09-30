package com.app.modules.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.testsupport.TestContainerImages;

/**
 * The V127 triggers: PostgreSQL bumps {@code admin_actions.row_version} and enqueues a replication
 * event in the same transaction whenever it rewrites an audit row itself, which the {@code ON
 * DELETE SET NULL} cascades do, and stays silent for an update that changes nothing.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminActionReplicationTriggerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final JdbcClient jdbcClient;

    AdminActionReplicationTriggerIT(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void insert_newAuditRow_startsAtVersionOneAndWritesNoOutboxRow() {
        UUID actionId = insertAdminAction(insertUser("trigger_insert_admin"), null);

        assertThat(rowVersion(actionId)).isEqualTo(1L);
        assertThat(changedEvents(actionId)).isEmpty();
    }

    @Test
    void cascade_deletingTheActingAdmin_bumpsTheVersionAndWritesOneOutboxRow() {
        UUID admin = insertUser("trigger_cascade_admin");
        UUID actionId = insertAdminAction(admin, null);

        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", admin).update();

        assertThat(rowVersion(actionId)).isEqualTo(2L);
        List<String> payloads = changedEvents(actionId);
        assertThat(payloads).hasSize(1);
        DomainEventEnvelope envelope = parse(payloads.get(0));
        assertThat(envelope.eventType()).isEqualTo(AdminEventTypes.ACTION_CHANGED_V1);
        assertThat(envelope.aggregateType()).isEqualTo("admin_action");
        assertThat(envelope.aggregateId()).isEqualTo(actionId);
        assertThat(envelope.actorId()).isNull();
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.data())
                .containsEntry("adminActionId", actionId.toString())
                .containsEntry("rowVersion", 2);
    }

    @Test
    void cascade_deletingTheActorAndThenTheTarget_countsEachRewrite() {
        UUID admin = insertUser("trigger_two_admin");
        UUID target = insertUser("trigger_two_target");
        UUID actionId = insertAdminAction(admin, target);

        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", admin).update();
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", target).update();

        assertThat(rowVersion(actionId)).isEqualTo(3L);
        assertThat(changedEvents(actionId)).hasSize(2);
    }

    @Test
    void update_thatChangesNothing_writesNoOutboxRowAndKeepsTheVersion() {
        UUID actionId = insertAdminAction(insertUser("trigger_noop_admin"), null);

        jdbcClient
                .sql("UPDATE admin_actions SET reason = reason WHERE id = :id")
                .param("id", actionId)
                .update();

        assertThat(rowVersion(actionId)).isEqualTo(1L);
        assertThat(changedEvents(actionId)).isEmpty();
    }

    @Test
    void update_underReplicaSessionRole_writesNoOutboxRow() {
        UUID admin = insertUser("trigger_replica_admin");
        UUID actionId = insertAdminAction(admin, null);

        // A seed reset or a bulk restore runs with triggers off, and must not flood the outbox.
        jdbcClient.sql("SET LOCAL session_replication_role = 'replica'").update();
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", admin).update();
        jdbcClient.sql("SET LOCAL session_replication_role = 'origin'").update();

        assertThat(changedEvents(actionId)).isEmpty();
    }

    private DomainEventEnvelope parse(String payload) {
        return new DomainEventMessageParser()
                .parse(new Message(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private long rowVersion(UUID actionId) {
        return jdbcClient
                .sql("SELECT row_version FROM admin_actions WHERE id = :id")
                .param("id", actionId)
                .query(Long.class)
                .single();
    }

    private List<String> changedEvents(UUID actionId) {
        return jdbcClient
                .sql(
                        "SELECT payload::text FROM outbox_events WHERE event_type ="
                                + " 'admin.action.changed.v1' AND aggregate_id = :id ORDER BY"
                                + " created_at, id")
                .param("id", actionId)
                .query(String.class)
                .list();
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        "INSERT INTO users(username, email, display_name) VALUES (:username,"
                                + " :email, :displayName) RETURNING id")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertAdminAction(UUID adminId, UUID targetUserId) {
        return jdbcClient
                .sql(
                        "INSERT INTO admin_actions(admin_id, action_type, target_user_id)"
                                + " VALUES (:adminId, 'ban_user', :targetUserId) RETURNING id")
                .param("adminId", adminId)
                .param("targetUserId", targetUserId)
                .query(UUID.class)
                .single();
    }
}
