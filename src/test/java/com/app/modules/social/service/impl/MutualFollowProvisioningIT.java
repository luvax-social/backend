package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.mail.service.MailService;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.social.service.SocialService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves a conversation appears the moment two people follow each other back, disappears if the
 * relationship ends before anything was said, and survives if anything was.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class MutualFollowProvisioningIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "mutual-follow-provisioning-secret-32-chars!!!");
        r.add("JWT_ISSUER", () -> "https://mutual-follow.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @MockitoBean private MailService mailService;

    @Autowired private SocialService socialService;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM conversation_participants");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void followingBack_provisionsExactlyOneConversation() {
        UUID alice = insertUser("prov_alice");
        UUID bob = insertUser("prov_bob");

        socialService.followUser(alice, bob);
        // One direction is not a relationship, so nothing should exist yet.
        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isEmpty();

        socialService.followUser(bob, alice);

        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isPresent();
        assertThat(countConversations(alice, bob)).isEqualTo(1);
    }

    @Test
    void approvingAFollowRequest_provisions() {
        UUID owner = insertPrivateUser("prov_owner");
        UUID fan = insertUser("prov_fan");

        // The owner follows the public account outright; the fan's request to the private account
        // stays pending, so the pair is not yet mutual.
        socialService.followUser(owner, fan);
        socialService.followUser(fan, owner);
        assertThat(conversationRepository.findDirectConversationBetween(owner, fan)).isEmpty();

        socialService.respondToFollowRequest(owner, fan, "approve");

        assertThat(conversationRepository.findDirectConversationBetween(owner, fan)).isPresent();
    }

    @Test
    void rejectingAFollowRequest_provisionsNothing() {
        UUID owner = insertPrivateUser("prov_owner2");
        UUID fan = insertUser("prov_fan2");
        socialService.followUser(owner, fan);
        socialService.followUser(fan, owner);

        socialService.respondToFollowRequest(owner, fan, "reject");

        assertThat(conversationRepository.findDirectConversationBetween(owner, fan)).isEmpty();
    }

    @Test
    void unfollowing_removesTheEmptyConversation() {
        UUID alice = insertUser("prov_alice3");
        UUID bob = insertUser("prov_bob3");
        socialService.followUser(alice, bob);
        socialService.followUser(bob, alice);

        socialService.unfollowUser(alice, bob);

        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isEmpty();
    }

    @Test
    void unfollowing_keepsAConversationThatHasMessages() {
        // The data-loss guard. Unfollowing must not destroy the record of what was said.
        UUID alice = insertUser("prov_alice4");
        UUID bob = insertUser("prov_bob4");
        socialService.followUser(alice, bob);
        socialService.followUser(bob, alice);
        UUID conversationId =
                conversationRepository
                        .findDirectConversationBetween(alice, bob)
                        .orElseThrow()
                        .getId();
        insertMessage(conversationId, alice);

        socialService.unfollowUser(alice, bob);

        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isPresent();
    }

    @Test
    void concurrentFollowBacks_produceOneConversation() throws Exception {
        // Two guards in one: the advisory lock inside the provisioner (without it both transactions
        // observe no conversation and insert one each), and the counter trigger's lock strength
        // (with the wrong one the two follows deadlock and one fails outright).
        //
        // Repeated because a single pass is not a reliable reproduction: the interleaving that
        // deadlocks showed up only under full-suite load the first time it was seen.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 12; attempt++) {
                UUID alice = insertUser("prov_race_a" + attempt);
                UUID bob = insertUser("prov_race_b" + attempt);
                CountDownLatch start = new CountDownLatch(1);

                Future<?> first =
                        pool.submit(
                                () -> {
                                    start.await();
                                    socialService.followUser(alice, bob);
                                    return null;
                                });
                Future<?> second =
                        pool.submit(
                                () -> {
                                    start.await();
                                    socialService.followUser(bob, alice);
                                    return null;
                                });
                start.countDown();
                first.get();
                second.get();

                assertThat(countConversations(alice, bob))
                        .as("attempt %d must leave exactly one conversation", attempt)
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdown();
        }
    }

    private long countConversations(UUID userA, UUID userB) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM conversations WHERE direct_pair_key ="
                                + " LEAST(?, ?)::text || ':' || GREATEST(?, ?)::text",
                        Long.class,
                        userA,
                        userB,
                        userA,
                        userB);
        return count == null ? 0 : count;
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username);
    }

    private UUID insertPrivateUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, is_private,"
                        + " deleted_at) VALUES (?, ?, ?, false, true, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username);
    }

    private void insertMessage(UUID conversationId, UUID senderId) {
        jdbcTemplate.update(
                "INSERT INTO messages(conversation_id, sender_id, message_type, content)"
                        + " VALUES (?, ?, 'text', 'said out loud')",
                conversationId,
                senderId);
    }
}
