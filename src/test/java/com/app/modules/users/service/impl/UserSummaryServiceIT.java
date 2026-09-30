package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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

import com.app.common.response.UserSummaryResponse;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class UserSummaryServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final UserRepository userRepository;
    private final JdbcClient jdbcClient;
    private final UserSummaryServiceImpl service;

    UserSummaryServiceIT(UserRepository userRepository, JdbcClient jdbcClient) {
        this.userRepository = userRepository;
        this.jdbcClient = jdbcClient;
        this.service = new UserSummaryServiceImpl(userRepository);
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void loadSummaries_liveUser_mapsPublicFields() {
        UUID id = insertUser("alice", "Alice", "https://cdn/alice.png", true, false);

        Map<UUID, UserSummaryResponse> result = service.loadSummaries(List.of(id));

        assertThat(result.get(id))
                .isEqualTo(
                        new UserSummaryResponse(
                                id, "alice", "Alice", "https://cdn/alice.png", true));
    }

    @Test
    void loadSummaries_deletedUser_returnsPlaceholder() {
        UUID id = insertUser("bob", "Bob", null, false, true);

        Map<UUID, UserSummaryResponse> result = service.loadSummaries(List.of(id));

        assertThat(result.get(id))
                .isEqualTo(
                        new UserSummaryResponse(
                                id,
                                null,
                                UserSummaryServiceImpl.DELETED_DISPLAY_NAME,
                                null,
                                false));
    }

    @Test
    void loadSummaries_unknownId_returnsPlaceholder() {
        UUID unknown = UUID.randomUUID();

        Map<UUID, UserSummaryResponse> result = service.loadSummaries(List.of(unknown));

        assertThat(result.get(unknown))
                .isEqualTo(
                        new UserSummaryResponse(
                                unknown,
                                null,
                                UserSummaryServiceImpl.DELETED_DISPLAY_NAME,
                                null,
                                false));
    }

    @Test
    void loadSummaries_mixedPageWithDuplicate_dedupsAndPlaceholders() {
        UUID alice = insertUser("carol", "Carol", null, false, false);
        UUID deleted = insertUser("dave", "Dave", null, false, true);

        Map<UUID, UserSummaryResponse> result =
                service.loadSummaries(List.of(alice, deleted, alice));

        assertThat(result).hasSize(2);
        assertThat(result.get(alice).username()).isEqualTo("carol");
        assertThat(result.get(deleted).displayName())
                .isEqualTo(UserSummaryServiceImpl.DELETED_DISPLAY_NAME);
        assertThat(result.get(deleted).username()).isNull();
    }

    @Test
    void findSummariesByIdIn_excludesDeleted() {
        UUID live = insertUser("erin", "Erin", null, false, false);
        UUID deleted = insertUser("frank", "Frank", null, false, true);

        List<UserSummaryResponse> found =
                userRepository.findSummariesByIdIn(List.of(live, deleted));

        assertThat(found).extracting(UserSummaryResponse::id).containsExactly(live);
    }

    private UUID insertUser(
            String username,
            String displayName,
            String avatarUrl,
            boolean isVerified,
            boolean deleted) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name, avatar_url, is_verified, deleted_at)
						VALUES (:username, :email, :displayName, :avatarUrl, :isVerified,
								CASE WHEN :deleted THEN NOW() ELSE NULL END)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", displayName)
                .param("avatarUrl", avatarUrl)
                .param("isVerified", isVerified)
                .param("deleted", deleted)
                .query(UUID.class)
                .single();
    }
}
