package com.app.modules.social.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.common.exception.GlobalExceptionHandler;
import com.app.common.response.ApiResponse;
import com.app.modules.social.entity.HiddenWord;
import com.app.modules.social.entity.UserHiddenWord;
import com.app.testsupport.TestContainerImages;

/**
 * Forces the database's own unique-constraint refusals for both hidden word tables and feeds the
 * real exceptions to the handler, so a constraint rename in a migration cannot silently turn a lost
 * race into a generic conflict.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class HiddenWordDuplicateConstraintIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final HiddenWordRepository hiddenWordRepository;
    private final UserHiddenWordRepository userHiddenWordRepository;
    private final JdbcClient jdbcClient;

    HiddenWordDuplicateConstraintIT(
            HiddenWordRepository hiddenWordRepository,
            UserHiddenWordRepository userHiddenWordRepository,
            JdbcClient jdbcClient) {
        this.hiddenWordRepository = hiddenWordRepository;
        this.userHiddenWordRepository = userHiddenWordRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void systemList_duplicateNormalizedPhrase_isAnsweredAsHiddenWordDuplicate() {
        hiddenWordRepository.saveAndFlush(word("Đồ Ngu", "do ngu"));

        DataIntegrityViolationException violation =
                catchViolation(() -> hiddenWordRepository.saveAndFlush(word("do ngu", "do ngu")));

        assertThat(answerFor(violation).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answerFor(violation).getBody().getCode()).isEqualTo("HIDDEN_WORD_DUPLICATE");
    }

    @Test
    void personalList_duplicateNormalizedPhrasePerUser_isAnsweredAsHiddenWordDuplicate() {
        UUID userId = insertUser("constraint_owner");
        userHiddenWordRepository.saveAndFlush(personal(userId, "Đồ Ngu", "do ngu"));

        DataIntegrityViolationException violation =
                catchViolation(
                        () ->
                                userHiddenWordRepository.saveAndFlush(
                                        personal(userId, "do ngu", "do ngu")));

        assertThat(answerFor(violation).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answerFor(violation).getBody().getCode()).isEqualTo("HIDDEN_WORD_DUPLICATE");
    }

    private ResponseEntity<ApiResponse<?>> answerFor(DataIntegrityViolationException violation) {
        return handler.handleDataIntegrityViolation(violation);
    }

    private static DataIntegrityViolationException catchViolation(Runnable write) {
        try {
            write.run();
        } catch (DataIntegrityViolationException expected) {
            return expected;
        }
        throw new AssertionError("the write was expected to violate a unique constraint");
    }

    private static HiddenWord word(String phrase, String normalized) {
        return HiddenWord.builder().phrase(phrase).normalizedPhrase(normalized).build();
    }

    private static UserHiddenWord personal(UUID userId, String phrase, String normalized) {
        return UserHiddenWord.builder()
                .userId(userId)
                .phrase(phrase)
                .normalizedPhrase(normalized)
                .build();
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO users (id, username, email, role, status) "
                                + "VALUES (:id, :name, :email, 'user', 'active')")
                .param("id", id)
                .param("name", name)
                .param("email", name + "@test.local")
                .update();
        return id;
    }
}
