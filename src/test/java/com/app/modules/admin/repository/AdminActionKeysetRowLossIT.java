package com.app.modules.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

import com.app.modules.admin.entity.AdminAction;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when admin actions share a boundary {@code created_at}. Pages with
 * a size that cuts into the tie-group and asserts every action is returned exactly once; a dropped
 * row proves a strict-inequality regression, a duplicated row a non-strict one. Exercises {@link
 * AdminActionRepositoryImpl#findActions} with every filter null, the same construction {@code
 * getActions} and {@code getActionsForUser} both narrow with a non-null filter.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminActionKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final AdminActionRepository adminActionRepository;
    private final JdbcClient jdbcClient;

    AdminActionKeysetRowLossIT(AdminActionRepository adminActionRepository, JdbcClient jdbcClient) {
        this.adminActionRepository = adminActionRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void actions_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID admin = insertUser("admin_actor");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID targetUser = insertUser("admin_target_" + i);
            UUID actionId = insertAdminAction(admin, targetUser, SHARED_INSTANT);
            expected.add(actionId);
        }

        List<UUID> seen = new ArrayList<>();
        List<AdminAction> page =
                adminActionRepository.findActions(
                        null, null, null, null, null, null, null, PAGE_SIZE);
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(a -> seen.add(a.getId()));
            AdminAction last = page.get(page.size() - 1);
            page =
                    adminActionRepository.findActions(
                            null,
                            null,
                            null,
                            null,
                            null,
                            last.getCreatedAt(),
                            last.getId(),
                            PAGE_SIZE);
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :displayName)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertAdminAction(UUID adminId, UUID targetUserId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO admin_actions(admin_id, action_type, target_user_id,"
                                + " created_at)"
                                + " VALUES (:adminId, 'ban_user', :targetUserId, :createdAt)"
                                + " RETURNING id")
                .param("adminId", adminId)
                .param("targetUserId", targetUserId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }
}
