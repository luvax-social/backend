package com.app.modules.social.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

import com.app.modules.social.enums.FollowStatus;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InteractionPolicyRepositoryQueriesIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final FollowRepository followRepository;
    private final InteractionLimitRepository limitRepository;
    private final JdbcClient jdbcClient;

    InteractionPolicyRepositoryQueriesIT(
            FollowRepository followRepository,
            InteractionLimitRepository limitRepository,
            JdbcClient jdbcClient) {
        this.followRepository = followRepository;
        this.limitRepository = limitRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void findAcceptedAt_acceptedFollow_returnsTriggerSetTime() {
        UUID follower = insertUser("policy_acc_a");
        UUID following = insertUser("policy_acc_b");
        followRepository.insert(follower, following, FollowStatus.ACCEPTED);

        assertThat(followRepository.findAcceptedAt(follower, following)).isPresent();
        assertThat(followRepository.findAcceptedAt(following, follower)).isEmpty();
    }

    @Test
    void findAcceptedAt_pendingFollow_isEmpty() {
        UUID follower = insertUser("policy_pen_a");
        UUID following = insertUser("policy_pen_b");
        followRepository.insert(follower, following, FollowStatus.PENDING);

        assertThat(followRepository.findAcceptedAt(follower, following)).isEmpty();
    }

    @Test
    void findActive_limitInForce_isReturned() {
        UUID user = insertUser("policy_lim_on");
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        limitRepository.upsert(user, true, false, now.plusDays(1));

        assertThat(limitRepository.findActive(user, now))
                .hasValueSatisfying(limit -> assertThat(limit.isLimitNonFollowers()).isTrue());
    }

    @Test
    void findActive_expiredLimit_isEmpty() {
        UUID user = insertUser("policy_lim_off");
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        limitRepository.upsert(user, true, true, now.minusSeconds(1));

        assertThat(limitRepository.findActive(user, now)).isEmpty();
    }

    @Test
    void findActive_limitEndingExactlyNow_isEmpty() {
        UUID user = insertUser("policy_lim_edge");
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        limitRepository.upsert(user, true, false, now);

        assertThat(limitRepository.findActive(user, now)).isEmpty();
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
}
