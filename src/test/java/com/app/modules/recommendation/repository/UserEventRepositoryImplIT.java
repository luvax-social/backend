package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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

import com.app.modules.recommendation.enums.UserEventType;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class UserEventRepositoryImplIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final UserEventRepository userEventRepository;
    private final JdbcClient jdbcClient;

    UserEventRepositoryImplIT(UserEventRepository userEventRepository, JdbcClient jdbcClient) {
        this.userEventRepository = userEventRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void findRecentEntityIds_boundedByWindow_excludesReadsOutsideIt() {
        UUID userId = insertUser("window_user");
        UUID insideWindowPost = UUID.randomUUID();
        UUID outsideWindowPost = UUID.randomUUID();
        insertUserEvent(
                userId,
                UserEventType.POST_VIEW,
                insideWindowPost,
                OffsetDateTime.now().minusDays(5));
        insertUserEvent(
                userId,
                UserEventType.POST_VIEW,
                outsideWindowPost,
                OffsetDateTime.now().minusDays(200));

        var ids =
                userEventRepository.findRecentEntityIds(
                        userId,
                        UserEventType.POST_VIEW,
                        OffsetDateTime.now().minusDays(90),
                        OffsetDateTime.now(),
                        2000);

        assertThat(ids).containsExactly(insideWindowPost);
    }

    @Test
    void findRecentEntityIds_capsAtLimit() {
        UUID userId = insertUser("cap_user");
        for (int i = 0; i < 5; i++) {
            insertUserEvent(
                    userId,
                    UserEventType.POST_VIEW,
                    UUID.randomUUID(),
                    OffsetDateTime.now().minusHours(i));
        }

        var ids =
                userEventRepository.findRecentEntityIds(
                        userId,
                        UserEventType.POST_VIEW,
                        OffsetDateTime.now().minusDays(1),
                        OffsetDateTime.now(),
                        3);

        assertThat(ids).hasSize(3);
    }

    @Test
    void findRecentEntityIds_filtersByEventType() {
        UUID userId = insertUser("type_user");
        UUID viewedPost = UUID.randomUUID();
        UUID likedPost = UUID.randomUUID();
        insertUserEvent(
                userId, UserEventType.POST_VIEW, viewedPost, OffsetDateTime.now().minusHours(1));
        insertUserEvent(
                userId, UserEventType.POST_LIKE, likedPost, OffsetDateTime.now().minusHours(1));

        var ids =
                userEventRepository.findRecentEntityIds(
                        userId,
                        UserEventType.POST_VIEW,
                        OffsetDateTime.now().minusDays(1),
                        OffsetDateTime.now(),
                        2000);

        assertThat(ids).containsExactly(viewedPost);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name, is_private)
						VALUES (:username, :email, :displayName, false)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private void insertUserEvent(
            UUID userId, UserEventType eventType, UUID entityId, OffsetDateTime createdAt) {
        jdbcClient
                .sql(
                        """
						INSERT INTO user_events(user_id, event_type, entity_type, entity_id, created_at)
						VALUES (:userId, CAST(:eventType AS event_type), 'post', :entityId, :createdAt)
						""")
                .param("userId", userId)
                .param("eventType", eventType.toJson())
                .param("entityId", entityId)
                .param("createdAt", createdAt)
                .update();
    }
}
