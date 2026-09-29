package com.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.persistence.EntityManager;

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

import com.app.modules.message.entity.Conversation;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

/**
 * Guards the {@code updated_at} invariant on the four tables carrying a {@code trg_*_updated_at}
 * trigger (V16): the trigger is the sole writer, and the entity must reflect exactly the
 * trigger-written value, never an independently computed application-side guess.
 *
 * <p>Before {@code @Generated} replaced {@code @UpdateTimestamp}, both Hibernate and the trigger
 * wrote this column on every update; the trigger always won at the database level, but Hibernate
 * never learned that and kept its own guess in the entity it returned to the caller. {@code
 * Conversation} carried neither annotation and so held null after an insert.
 *
 * <p>Also guards the companion invariant that {@code created_at} and {@code updated_at} agree at
 * insert. Both columns default to {@code NOW()}, which is the transaction timestamp, so a freshly
 * inserted row must carry the same value in each. They previously disagreed by tens or hundreds of
 * milliseconds because {@code created_at} came from the JVM clock and {@code updated_at} from
 * Postgres, which made every row look modified from the moment it existed.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class UpdatedAtSingleWriterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final ConversationRepository conversationRepository;
    private final JdbcClient jdbcClient;
    private final EntityManager entityManager;

    UpdatedAtSingleWriterIT(
            UserRepository userRepository,
            PostRepository postRepository,
            ConversationRepository conversationRepository,
            JdbcClient jdbcClient,
            EntityManager entityManager) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.conversationRepository = conversationRepository;
        this.jdbcClient = jdbcClient;
        this.entityManager = entityManager;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void insert_entityUpdatedAtMatchesTheDatabaseValueExactly() {
        User user =
                userRepository.save(
                        User.builder()
                                .username(uniqueUsername())
                                .email(UUID.randomUUID() + "@test.local")
                                .role(UserRole.USER)
                                .status(UserStatus.ACTIVE)
                                .isPrivate(false)
                                .isVerified(false)
                                .build());
        entityManager.flush();

        OffsetDateTime actualDbValue = actualUpdatedAt(user.getId());

        assertThat(user.getUpdatedAt()).isEqualTo(actualDbValue);
    }

    @Test
    void update_entityUpdatedAtMatchesTheDatabaseValueExactly() {
        User user =
                userRepository.save(
                        User.builder()
                                .username(uniqueUsername())
                                .email(UUID.randomUUID() + "@test.local")
                                .role(UserRole.USER)
                                .status(UserStatus.ACTIVE)
                                .isPrivate(false)
                                .isVerified(false)
                                .build());

        entityManager.flush();
        user.setDisplayName("changed");
        User updated = userRepository.save(user);
        entityManager.flush();

        OffsetDateTime actualDbValue = actualUpdatedAt(updated.getId());

        assertThat(updated.getUpdatedAt()).isEqualTo(actualDbValue);
    }

    @Test
    void insert_userTimestampsComeFromTheSameClock() {
        User user = userRepository.save(newUser());
        entityManager.flush();

        assertThat(user.getCreatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(timestamps("users", user.getId())).satisfies(SAME_CLOCK);
    }

    @Test
    void insert_postTimestampsComeFromTheSameClock() {
        User author = userRepository.save(newUser());
        entityManager.flush();

        Post post =
                postRepository.save(
                        Post.builder()
                                .userId(author.getId())
                                .caption("timestamp agreement post")
                                .postType(PostType.IMAGE)
                                .status(PostStatus.PUBLISHED)
                                .build());
        entityManager.flush();

        assertThat(post.getCreatedAt()).isEqualTo(post.getUpdatedAt());
        assertThat(timestamps("posts", post.getId())).satisfies(SAME_CLOCK);
    }

    @Test
    void insert_conversationTimestampsComeFromTheSameClock() {
        Conversation conversation = conversationRepository.save(Conversation.builder().build());
        entityManager.flush();

        assertThat(conversation.getCreatedAt()).isEqualTo(conversation.getUpdatedAt());
        assertThat(timestamps("conversations", conversation.getId())).satisfies(SAME_CLOCK);
    }

    @Test
    void insert_conversationEntityCarriesTheDatabaseUpdatedAt() {
        Conversation conversation = conversationRepository.save(Conversation.builder().build());
        entityManager.flush();

        // The column is NOT NULL DEFAULT NOW(), so the row always has a value; without
        // @Generated the entity never re-reads it and holds null instead.
        assertThat(conversation.getUpdatedAt()).isNotNull();
        assertThat(conversation.getUpdatedAt())
                .isEqualTo(
                        jdbcClient
                                .sql("SELECT updated_at FROM conversations WHERE id = :id")
                                .param("id", conversation.getId())
                                .query(OffsetDateTime.class)
                                .single());
    }

    /** The two columns of one row, so a single read can compare them against each other. */
    private record Timestamps(OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    private static final Consumer<Timestamps> SAME_CLOCK =
            row -> assertThat(row.createdAt()).isEqualTo(row.updatedAt());

    private Timestamps timestamps(String table, UUID id) {
        return jdbcClient
                .sql("SELECT created_at, updated_at FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(Timestamps.class)
                .single();
    }

    private static User newUser() {
        return User.builder()
                .username(uniqueUsername())
                .email(UUID.randomUUID() + "@test.local")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .isPrivate(false)
                .isVerified(false)
                .build();
    }

    private static String uniqueUsername() {
        return "uat_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private OffsetDateTime actualUpdatedAt(UUID userId) {
        return jdbcClient
                .sql("SELECT updated_at FROM users WHERE id = :id")
                .param("id", userId)
                .query(OffsetDateTime.class)
                .single();
    }
}
