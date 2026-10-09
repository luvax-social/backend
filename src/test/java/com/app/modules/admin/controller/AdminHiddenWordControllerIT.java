package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.mail.service.MailService;
import com.app.modules.social.service.HiddenWordService;
import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class AdminHiddenWordControllerIT {

    private static final String HIDDEN_WORDS = "/api/v1/admin/hidden-words";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "admin-hidden-word-it-secret-32-chars-min!!");
        registry.add("JWT_ISSUER", () -> "https://adminhiddenword.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private HiddenWordService hiddenWordService;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM hidden_words");
        jdbcTemplate.update("DELETE FROM users");
        hiddenWordService.refreshSystemList();
    }

    @Test
    void createHiddenWord_administrator_writesTheRowAndOneAuditRow() {
        TestUser admin = createUser("hw_create_admin", "admin");

        ResponseEntity<Map> response =
                post(HIDDEN_WORDS, Map.of("phrase", "  Đồ  Ngu ", "reason", "abuse"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("phrase")).isEqualTo("Đồ  Ngu");
        assertThat(data.get("normalizedPhrase")).isEqualTo("do ngu");
        assertThat(data.get("createdBy")).isEqualTo(admin.id().toString());
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT id, phrase, normalized_phrase, created_by FROM hidden_words");
        assertThat(row.get("normalized_phrase")).isEqualTo("do ngu");
        assertThat(row.get("created_by")).isEqualTo(admin.id());
        List<Map<String, Object>> audit = auditRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("action_type")).isEqualTo("create_hidden_word");
        assertThat(audit.get(0).get("target_entity_type")).isEqualTo("hidden_word");
        assertThat(audit.get(0).get("target_entity_id")).isEqualTo(row.get("id"));
        assertThat(audit.get(0).get("admin_id")).isEqualTo(admin.id());
        assertThat(audit.get(0).get("reason")).isEqualTo("abuse");
        assertThat(audit.get(0).get("phrase")).isEqualTo("Đồ  Ngu");
        assertThat(audit.get(0).get("normalized_phrase")).isEqualTo("do ngu");
    }

    @Test
    void createHiddenWord_committed_reachesTheSystemMatcherWithoutARestart() {
        TestUser admin = createUser("hw_cache_admin", "admin");
        // This account has no user_settings row, so the matcher also exercises the column defaults.
        TestUser target = createUser("hw_cache_target", "user");
        assertThat(
                        hiddenWordService
                                .matcherFor(target.id())
                                .matches(TextMatchNormalizer.tokens("a vexillum here")))
                .isFalse();

        post(HIDDEN_WORDS, Map.of("phrase", "Vexillum", "reason", "test"), admin);

        assertThat(
                        hiddenWordService
                                .matcherFor(target.id())
                                .matches(TextMatchNormalizer.tokens("a vexillum here")))
                .isTrue();
    }

    @Test
    void createHiddenWord_moderator_returnsForbiddenAndWritesNothing() {
        TestUser moderator = createUser("hw_create_mod", "moderator");

        ResponseEntity<Map> response =
                post(HIDDEN_WORDS, Map.of("phrase", "nope", "reason", "r"), moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("hidden_words")).isZero();
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void everyHiddenWordEndpoint_moderator_returnsForbidden() {
        TestUser moderator = createUser("hw_all_mod", "moderator");
        UUID id = UUID.randomUUID();

        assertThat(get(HIDDEN_WORDS, moderator).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        patch(
                                        HIDDEN_WORDS + "/" + id,
                                        Map.of("phrase", "x", "reason", "r"),
                                        moderator)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        delete(HIDDEN_WORDS + "/" + id, Map.of("reason", "r"), moderator)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void hiddenWordEndpoints_withoutToken_returnUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThat(
                        rest.exchange(
                                        HIDDEN_WORDS,
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createHiddenWord_sameNormalisedFormAsAnExistingWord_returnsConflictAndNoAuditRow() {
        TestUser admin = createUser("hw_dup_admin", "admin");
        post(HIDDEN_WORDS, Map.of("phrase", "Đồ Ngu", "reason", "first"), admin);

        ResponseEntity<Map> duplicate =
                post(HIDDEN_WORDS, Map.of("phrase", "do ngu!", "reason", "again"), admin);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code")).isEqualTo("HIDDEN_WORD_DUPLICATE");
        assertThat(count("hidden_words")).isEqualTo(1);
        assertThat(auditRows()).hasSize(1);
    }

    @Test
    void createHiddenWord_invalidPhrases_returnBadRequestAndNoAuditRow() {
        TestUser admin = createUser("hw_invalid_admin", "admin");

        for (String phrase :
                new String[] {
                    "?!...",
                    "   ",
                    "a".repeat(65),
                    "one two three four five six seven",
                    "bad\u0000word",
                    "tab\tseparated"
                }) {
            ResponseEntity<Map> response =
                    post(HIDDEN_WORDS, Map.of("phrase", phrase, "reason", "r"), admin);

            assertThat(response.getStatusCode())
                    .as("phrase %s", phrase)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("HIDDEN_WORD_INVALID");
        }
        assertThat(count("hidden_words")).isZero();
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void createHiddenWord_missingReason_returnsValidationError() {
        TestUser admin = createUser("hw_reason_admin", "admin");

        ResponseEntity<Map> response = post(HIDDEN_WORDS, Map.of("phrase", "word"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(count("hidden_words")).isZero();
    }

    @Test
    void updateHiddenWord_changesThePhraseAndRecordsPreviousValues() {
        TestUser admin = createUser("hw_edit_admin", "admin");
        UUID id = insertWord("Old Phrase");

        ResponseEntity<Map> response =
                patch(
                        HIDDEN_WORDS + "/" + id,
                        Map.of("phrase", "New Phrase", "reason", "reworded"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("phrase")).isEqualTo("New Phrase");
        assertThat(data.get("normalizedPhrase")).isEqualTo("new phrase");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT normalized_phrase FROM hidden_words WHERE id = ?",
                                String.class,
                                id))
                .isEqualTo("new phrase");
        List<Map<String, Object>> audit = auditRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("action_type")).isEqualTo("edit_hidden_word");
        assertThat(audit.get(0).get("target_entity_id")).isEqualTo(id);
        assertThat(audit.get(0).get("phrase")).isEqualTo("New Phrase");
        assertThat(audit.get(0).get("normalized_phrase")).isEqualTo("new phrase");
        assertThat(audit.get(0).get("previous_phrase")).isEqualTo("Old Phrase");
        assertThat(audit.get(0).get("previous_normalized_phrase")).isEqualTo("old phrase");
    }

    @Test
    void updateHiddenWord_retypingTheSameWordWithDifferentCase_isNotADuplicateOfItself() {
        TestUser admin = createUser("hw_self_admin", "admin");
        UUID id = insertWord("do ngu");

        ResponseEntity<Map> response =
                patch(
                        HIDDEN_WORDS + "/" + id,
                        Map.of("phrase", "Đồ Ngu", "reason", "restored accents"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody().get("data")).get("phrase")).isEqualTo("Đồ Ngu");
    }

    @Test
    void updateHiddenWord_toAnotherWordsNormalisedForm_returnsConflictAndNoAuditRow() {
        TestUser admin = createUser("hw_edit_dup_admin", "admin");
        insertWord("taken");
        UUID id = insertWord("free");

        ResponseEntity<Map> response =
                patch(HIDDEN_WORDS + "/" + id, Map.of("phrase", "Taken", "reason", "r"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("HIDDEN_WORD_DUPLICATE");
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void updateHiddenWord_unknownId_returnsNotFound() {
        TestUser admin = createUser("hw_edit_none_admin", "admin");

        ResponseEntity<Map> response =
                patch(
                        HIDDEN_WORDS + "/" + UUID.randomUUID(),
                        Map.of("phrase", "x", "reason", "r"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void deleteHiddenWord_removesTheRowAndRecordsItInTheAuditMetadata() {
        TestUser admin = createUser("hw_delete_admin", "admin");
        UUID id = insertWord("Remove Me");

        ResponseEntity<Map> response =
                delete(HIDDEN_WORDS + "/" + id, Map.of("reason", "false positive"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("hidden_words")).isZero();
        List<Map<String, Object>> audit = auditRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("action_type")).isEqualTo("delete_hidden_word");
        assertThat(audit.get(0).get("target_entity_id")).isEqualTo(id);
        assertThat(audit.get(0).get("reason")).isEqualTo("false positive");
        assertThat(audit.get(0).get("phrase")).isEqualTo("Remove Me");
        assertThat(audit.get(0).get("normalized_phrase")).isEqualTo("remove me");
    }

    @Test
    void deleteHiddenWord_unknownId_returnsNotFoundAndNoAuditRow() {
        TestUser admin = createUser("hw_delete_none_admin", "admin");

        ResponseEntity<Map> response =
                delete(HIDDEN_WORDS + "/" + UUID.randomUUID(), Map.of("reason", "r"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void listHiddenWords_pagesNewestFirstAcrossTiedCreationTimes() {
        TestUser admin = createUser("hw_list_admin", "admin");
        for (int i = 0; i < 5; i++) {
            insertWord("tied word " + i, "2026-01-01T00:00:00Z");
        }

        ResponseEntity<Map> first = get(HIDDEN_WORDS + "?limit=2", admin);
        String endCursor = (String) pageInfoOf(first).get("endCursor");
        ResponseEntity<Map> second = get(HIDDEN_WORDS + "?limit=2&cursor=" + endCursor, admin);
        String lastCursor = (String) pageInfoOf(second).get("endCursor");
        ResponseEntity<Map> third = get(HIDDEN_WORDS + "?limit=2&cursor=" + lastCursor, admin);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> all = new ArrayList<>(idsOf(first));
        all.addAll(idsOf(second));
        all.addAll(idsOf(third));
        assertThat(all).hasSize(5).doesNotHaveDuplicates();
        assertThat(pageInfoOf(first).get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfoOf(third).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    void listHiddenWords_queryNarrowsByPhraseIgnoringCaseAndAccents() {
        TestUser admin = createUser("hw_search_admin", "admin");
        insertWord("Đồ Ngu");
        insertWord("Scam link");
        insertWord("unrelated");

        ResponseEntity<Map> accents = get(HIDDEN_WORDS + "?q=NGU", admin);
        ResponseEntity<Map> scam = get(HIDDEN_WORDS + "?q=scam", admin);

        assertThat(phrasesOf(accents)).containsExactly("Đồ Ngu");
        assertThat(phrasesOf(scam)).containsExactly("Scam link");
    }

    @Test
    void listHiddenWords_malformedCursor_returnsBadRequest() {
        TestUser admin = createUser("hw_cursor_admin", "admin");

        ResponseEntity<Map> response = get(HIDDEN_WORDS + "?cursor=not-a-cursor", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID insertWord(String phrase) {
        return insertWord(phrase, null);
    }

    private UUID insertWord(String phrase, String createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO hidden_words (phrase, normalized_phrase, created_at) "
                        + "VALUES (?, ?, COALESCE(CAST(? AS timestamptz), NOW())) RETURNING id",
                UUID.class,
                phrase,
                TextMatchNormalizer.normalizePhrase(phrase),
                createdAt);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> auditRows() {
        return jdbcTemplate.queryForList(
                "SELECT action_type::text AS action_type, target_entity_type, target_entity_id,"
                        + " admin_id, reason, metadata->>'phrase' AS phrase,"
                        + " metadata->>'normalizedPhrase' AS normalized_phrase,"
                        + " metadata->>'previousPhrase' AS previous_phrase,"
                        + " metadata->>'previousNormalizedPhrase' AS previous_normalized_phrase"
                        + " FROM admin_actions ORDER BY created_at");
    }

    private TestUser createUser(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local",
                role);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private ResponseEntity<Map> get(String path, TestUser user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> patch(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> delete(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<String, Object>>) data.get("content");
    }

    private static Map<?, ?> pageInfoOf(ResponseEntity<Map> response) {
        return (Map<?, ?>) ((Map<?, ?>) response.getBody().get("data")).get("pageInfo");
    }

    private static List<String> idsOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("id")).toList();
    }

    private static List<String> phrasesOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("phrase")).toList();
    }
}
