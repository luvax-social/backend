package com.app.modules.support.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/**
 * The two support invariants a unit test cannot reach.
 *
 * <p>The one-open-ticket guard and the guarded claim are both concurrency behaviours enforced by
 * PostgreSQL: a partial unique index and an UPDATE predicate. A mocked repository asserts only the
 * mock, so both need a real database. Verification now depends on both, because a verification
 * request is a ticket on this framework.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SupportTicketConcurrencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final SupportTicketRepository supportTicketRepository;
    private final JdbcClient jdbcClient;

    SupportTicketConcurrencyIT(
            SupportTicketRepository supportTicketRepository, JdbcClient jdbcClient) {
        this.supportTicketRepository = supportTicketRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void secondOpenSupportTicket_isRefusedByTheDatabase() {
        UUID user = insertUser("one_open");
        insertTicket(user, "bug_report", "open");

        assertThatThrownBy(() -> insertTicket(user, "account_access", "open"))
                .hasMessageContaining("uq_support_tickets_one_open_support_per_user");
    }

    @Test
    void terminalTicket_doesNotBlockANewOne() {
        // V107 narrowed the guard to active statuses for the same reason V89 narrowed the report
        // duplicate guard: a guard that counts closed rows stops a user ever asking again.
        UUID user = insertUser("closed_then_new");
        insertTicket(user, "bug_report", "answered");

        assertThat(insertTicket(user, "bug_report", "open")).isNotNull();
    }

    @Test
    void verificationRequest_doesNotBlockASupportTicket() {
        // The priority inversion V107 exists to remove: a pending request for a badge must not
        // stand in the way of contesting a ban.
        UUID user = insertUser("verif_then_appeal");
        insertTicket(user, "verification_request", "open");

        assertThat(insertTicket(user, "appeal_ban", "open")).isNotNull();
        assertThat(supportTicketRepository.hasOpenTicket(user)).isTrue();
        assertThat(supportTicketRepository.hasOpenVerificationRequest(user)).isTrue();
    }

    @Test
    void supportTicket_doesNotBlockAVerificationRequest() {
        UUID user = insertUser("appeal_then_verif");
        insertTicket(user, "appeal_ban", "open");

        assertThat(insertTicket(user, "verification_request", "open")).isNotNull();
    }

    @Test
    void secondVerificationRequest_isRefusedByItsOwnGuard() {
        // Each lane still holds one ticket at a time; the guard is split, not removed.
        UUID user = insertUser("two_verifications");
        insertTicket(user, "verification_request", "open");

        assertThatThrownBy(() -> insertTicket(user, "verification_request", "open"))
                .hasMessageContaining("uq_support_tickets_one_open_verification_per_user");
    }

    @Test
    void hasOpenTicket_ignoresVerificationRequests() {
        // The service-layer check has to agree with the index. If it counted verification requests
        // the database would admit a ticket the service refused, which is worse than either rule
        // alone.
        UUID user = insertUser("service_guard_agrees");
        insertTicket(user, "verification_request", "open");

        assertThat(supportTicketRepository.hasOpenTicket(user)).isFalse();
    }

    @Test
    void claimIfUnassigned_secondClaimerUpdatesNothing() {
        UUID user = insertUser("claim_race");
        UUID staffOne = insertUser("staff_one");
        UUID staffTwo = insertUser("staff_two");
        UUID ticket = insertTicket(user, "verification_request", "open");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(supportTicketRepository.claimIfUnassigned(ticket, staffOne, now)).isEqualTo(1);
        // The predicate re-checks that the ticket is still unassigned, so the loser is told rather
        // than silently overwriting the winner.
        assertThat(supportTicketRepository.claimIfUnassigned(ticket, staffTwo, now)).isZero();

        UUID holder =
                jdbcClient
                        .sql("SELECT assigned_to FROM support_tickets WHERE id = :id")
                        .param("id", ticket)
                        .query(UUID.class)
                        .single();
        assertThat(holder).isEqualTo(staffOne);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :username)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .query(UUID.class)
                .single();
    }

    private UUID insertTicket(UUID userId, String category, String status) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO support_tickets
							(user_id, contact_email, category, subject, body, status, source)
						VALUES
							(:userId, 'x@example.com', :category, 'subject', 'body', :status,
							'authenticated')
						RETURNING id
						""")
                .param("userId", userId)
                .param("category", category)
                .param("status", status)
                .query(UUID.class)
                .single();
    }
}
