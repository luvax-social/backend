package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.service.DirectConversationProvisioner;
import com.app.testsupport.TestContainerImages;

/**
 * Provisioning, idempotency and teardown for the conversation implied by a mutual follow.
 *
 * <p>Loaded with {@code @Import} rather than {@code @SpringBootTest} because the provisioner is
 * deliberately built from repositories alone. If this test ever needs the full context to start,
 * the implementation has grown a dependency it was designed not to have.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@Import(DirectConversationProvisionerImpl.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DirectConversationProvisionerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final DirectConversationProvisioner provisioner;
    private final ConversationRepository conversationRepository;
    private final JdbcClient jdbcClient;

    private UUID alice;
    private UUID bob;

    DirectConversationProvisionerIT(
            DirectConversationProvisioner provisioner,
            ConversationRepository conversationRepository,
            JdbcClient jdbcClient) {
        this.provisioner = provisioner;
        this.conversationRepository = conversationRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach
    void setUp() {
        alice = insertUser("alice_" + UUID.randomUUID().toString().substring(0, 8));
        bob = insertUser("bob_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void createsOneConversationForThePair() {
        provisioner.ensureDirectConversation(alice, bob);

        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isPresent();
    }

    @Test
    void isIdempotentAndOrderIndependent() {
        provisioner.ensureDirectConversation(alice, bob);
        UUID first =
                conversationRepository
                        .findDirectConversationBetween(alice, bob)
                        .orElseThrow()
                        .getId();

        // Reversed arguments: the pair key is derived with LEAST/GREATEST, so this must find the
        // same conversation rather than create a mirrored second one.
        provisioner.ensureDirectConversation(bob, alice);

        UUID second =
                conversationRepository
                        .findDirectConversationBetween(alice, bob)
                        .orElseThrow()
                        .getId();
        assertThat(second).isEqualTo(first);
        assertThat(countConversationsForPair()).isEqualTo(1);
    }

    @Test
    void ignoresAPairOfTheSameUser() {
        provisioner.ensureDirectConversation(alice, alice);

        assertThat(conversationRepository.findDirectConversationBetween(alice, alice)).isEmpty();
    }

    @Test
    void discardsAnEmptyConversation() {
        provisioner.ensureDirectConversation(alice, bob);

        provisioner.discardEmptyDirectConversation(alice, bob);

        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isEmpty();
    }

    @Test
    void keepsAConversationThatHasMessages() {
        // The data-loss guard. Ending a follow must never destroy the record of what was said.
        provisioner.ensureDirectConversation(alice, bob);
        UUID conversationId =
                conversationRepository
                        .findDirectConversationBetween(alice, bob)
                        .orElseThrow()
                        .getId();
        insertMessage(conversationId, alice);

        provisioner.discardEmptyDirectConversation(alice, bob);

        assertThat(conversationRepository.findDirectConversationBetween(alice, bob)).isPresent();
    }

    @Test
    void theRecreatedUniqueIndexStillRejectsADuplicatePair() {
        // V50 drops is_group, which the original partial index was predicated on. If the migration
        // failed to recreate that index, this insert succeeds and a pair silently holds two
        // conversations, which no application-level lock can undo afterwards.
        provisioner.ensureDirectConversation(alice, bob);
        String pairKey =
                jdbcClient
                        .sql(
                                "SELECT LEAST(:userA, :userB)::text || ':' ||"
                                        + " GREATEST(:userA, :userB)::text")
                        .param("userA", alice)
                        .param("userB", bob)
                        .query(String.class)
                        .single();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                "INSERT INTO conversations (direct_pair_key,"
                                                        + " created_by) VALUES (:key, :createdBy)")
                                        .param("key", pairKey)
                                        .param("createdBy", alice)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long countConversationsForPair() {
        return jdbcClient
                .sql(
                        """
						SELECT COUNT(*) FROM conversations
						WHERE direct_pair_key = LEAST(:userA, :userB)::text || ':' || GREATEST(:userA, :userB)::text
						""")
                .param("userA", alice)
                .param("userB", bob)
                .query(Long.class)
                .single();
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

    private void insertMessage(UUID conversationId, UUID senderId) {
        jdbcClient
                .sql(
                        """
						INSERT INTO messages(conversation_id, sender_id, message_type, content)
						VALUES (:conversationId, :senderId, 'text', 'you cannot delete me')
						""")
                .param("conversationId", conversationId)
                .param("senderId", senderId)
                .update();
    }
}
