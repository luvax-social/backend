package com.app.modules.users.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.text.PhraseMatcher;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.auth.service.TokenService;
import com.app.modules.social.entity.HiddenWord;
import com.app.modules.social.entity.UserHiddenWord;
import com.app.modules.social.event.HiddenWordsChangedEvent;
import com.app.modules.social.repository.HiddenWordRepository;
import com.app.modules.social.repository.UserHiddenWordRepository;
import com.app.modules.social.service.HiddenWordService;
import com.app.modules.users.repository.UserRepository;
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
class UserHiddenWordControllerIT {

    private static final String TEST_PASSWORD = "S3cur3P@ssword";
    private static final String HIDDEN_WORDS = "/api/v1/users/me/hidden-words";
    private static final String SETTINGS = "/api/v1/users/me/settings";

    private static final String TEST_JWT_SECRET = "integration-test-secret-32-chars-minimum-len!!";
    private static final String TEST_JWT_ISSUER = "https://it.test.local";
    private static final String TEST_JWT_AUDIENCE = "App";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> TEST_JWT_SECRET);
        r.add("JWT_ISSUER", () -> TEST_JWT_ISSUER);
        r.add("JWT_AUDIENCE", () -> TEST_JWT_AUDIENCE);
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
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private HiddenWordService hiddenWordService;
    @Autowired private HiddenWordRepository hiddenWordRepository;
    @Autowired private UserHiddenWordRepository userHiddenWordRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void addHiddenWord_validPhrase_returns201AndAppearsInTheList() {
        Account account = newAccount("hw_add");

        ResponseEntity<Map> created = post(HIDDEN_WORDS, Map.of("phrase", "Đồ Ngu"), account);
        ResponseEntity<Map> listed = get(HIDDEN_WORDS, account);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> entry = (Map<?, ?>) created.getBody().get("data");
        assertThat(entry.get("phrase")).isEqualTo("Đồ Ngu");
        assertThat(entry.get("id")).isNotNull();
        assertThat(entry.get("createdAt")).isNotNull();
        Map<?, ?> data = (Map<?, ?>) listed.getBody().get("data");
        assertThat(data.get("limit")).isEqualTo(200);
        assertThat((List<?>) data.get("entries")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) data.get("entries")).get(0)).get("id"))
                .isEqualTo(entry.get("id"));
    }

    @Test
    void addHiddenWord_sameNormalisedFormAsExistingEntry_returns409() {
        Account account = newAccount("hw_dup");
        post(HIDDEN_WORDS, Map.of("phrase", "Đồ Ngu"), account);

        ResponseEntity<Map> duplicate = post(HIDDEN_WORDS, Map.of("phrase", "do ngu"), account);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code")).isEqualTo("HIDDEN_WORD_DUPLICATE");
        Map<?, ?> data = (Map<?, ?>) get(HIDDEN_WORDS, account).getBody().get("data");
        assertThat((List<?>) data.get("entries")).hasSize(1);
    }

    @Test
    void addHiddenWord_samePhraseOnAnotherAccount_isAccepted() {
        Account first = newAccount("hw_shared_a");
        Account second = newAccount("hw_shared_b");
        post(HIDDEN_WORDS, Map.of("phrase", "same phrase"), first);

        ResponseEntity<Map> response = post(HIDDEN_WORDS, Map.of("phrase", "same phrase"), second);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addHiddenWord_twoHundredAndFirstEntry_returns422() {
        Account account = newAccount("hw_limit");
        fillList(account.userId(), 200);

        ResponseEntity<Map> response =
                post(HIDDEN_WORDS, Map.of("phrase", "one too many"), account);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("code")).isEqualTo("HIDDEN_WORD_LIMIT_REACHED");
        assertThat(userHiddenWordRepository.countByUserId(account.userId())).isEqualTo(200);
    }

    @Test
    void addHiddenWord_concurrentRequestsAtTheLimit_admitExactlyOne() throws Exception {
        Account account = newAccount("hw_race");
        fillList(account.userId(), 199);
        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String phrase = "racer " + i;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return HttpStatus.valueOf(
                                            post(HIDDEN_WORDS, Map.of("phrase", phrase), account)
                                                    .getStatusCode()
                                                    .value());
                                }));
            }
            start.countDown();
            int created = 0;
            for (Future<HttpStatus> result : results) {
                if (result.get() == HttpStatus.CREATED) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(userHiddenWordRepository.countByUserId(account.userId())).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void addHiddenWord_invalidPhrases_return400() {
        Account account = newAccount("hw_invalid");

        for (Map<String, Object> body :
                List.<Map<String, Object>>of(
                        Map.of("phrase", "?!..."),
                        Map.of("phrase", "   "),
                        Map.of("phrase", ""),
                        Map.of("phrase", "a".repeat(65)),
                        Map.of("phrase", "one two three four five six seven"),
                        Map.of())) {
            ResponseEntity<Map> response = post(HIDDEN_WORDS, body, account);

            assertThat(response.getStatusCode())
                    .as("body %s", body)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("HIDDEN_WORD_INVALID");
        }
        assertThat(userHiddenWordRepository.countByUserId(account.userId())).isZero();
    }

    @Test
    void deleteHiddenWord_ownEntry_returns204AndRemovesIt() {
        Account account = newAccount("hw_del");
        String id = entryId(post(HIDDEN_WORDS, Map.of("phrase", "remove me"), account));

        ResponseEntity<Map> deleted = delete(HIDDEN_WORDS + "/" + id, account);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<?, ?> data = (Map<?, ?>) get(HIDDEN_WORDS, account).getBody().get("data");
        assertThat((List<?>) data.get("entries")).isEmpty();
    }

    @Test
    void deleteHiddenWord_entryOfAnotherAccount_returns404AndKeepsIt() {
        Account owner = newAccount("hw_del_owner");
        Account other = newAccount("hw_del_other");
        String id = entryId(post(HIDDEN_WORDS, Map.of("phrase", "not yours"), owner));

        ResponseEntity<Map> response = delete(HIDDEN_WORDS + "/" + id, other);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> data = (Map<?, ?>) get(HIDDEN_WORDS, owner).getBody().get("data");
        assertThat((List<?>) data.get("entries")).hasSize(1);
    }

    @Test
    void deleteHiddenWord_unknownEntry_returns404() {
        Account account = newAccount("hw_del_none");

        ResponseEntity<Map> response = delete(HIDDEN_WORDS + "/" + UUID.randomUUID(), account);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void hiddenWordEndpoints_withoutToken_return401() {
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
        assertThat(
                        rest.exchange(
                                        HIDDEN_WORDS,
                                        HttpMethod.POST,
                                        new HttpEntity<>(Map.of("phrase", "x"), headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        HIDDEN_WORDS + "/" + UUID.randomUUID(),
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void updateMySettings_hiddenWordsSystemEnabled_isPersistedAndDefaultsToTrue() {
        Account account = newAccount("hw_toggle");

        Map<?, ?> initial = (Map<?, ?>) get(SETTINGS, account).getBody().get("data");
        ResponseEntity<Map> updated =
                patch(SETTINGS, Map.of("hiddenWordsSystemEnabled", false), account);
        Map<?, ?> reread = (Map<?, ?>) get(SETTINGS, account).getBody().get("data");
        patch(SETTINGS, Map.of("notifyLikes", false), account);
        Map<?, ?> afterUnrelatedUpdate = (Map<?, ?>) get(SETTINGS, account).getBody().get("data");

        assertThat(initial.get("hiddenWordsSystemEnabled")).isEqualTo(true);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) updated.getBody().get("data")).get("hiddenWordsSystemEnabled"))
                .isEqualTo(false);
        assertThat(reread.get("hiddenWordsSystemEnabled")).isEqualTo(false);
        assertThat(afterUnrelatedUpdate.get("hiddenWordsSystemEnabled")).isEqualTo(false);
    }

    @Test
    void matcherFor_personalAndSystemLists_followTheToggleAndTheChangeEvent() {
        Account account = newAccount("hw_matcher");
        post(HIDDEN_WORDS, Map.of("phrase", "Đồ Ngu"), account);
        HiddenWord systemWord =
                hiddenWordRepository.save(
                        HiddenWord.builder()
                                .phrase("Vexillum")
                                .normalizedPhrase("vexillum")
                                .build());
        try {
            // The cached system list only changes when the change event commits.
            PhraseMatcher beforeEvent = hiddenWordService.matcherFor(account.userId());
            assertThat(beforeEvent.matches(TextMatchNormalizer.tokens("a vexillum here")))
                    .isFalse();

            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> eventPublisher.publishEvent(new HiddenWordsChangedEvent()));

            PhraseMatcher enabled = hiddenWordService.matcherFor(account.userId());
            assertThat(enabled.matches(TextMatchNormalizer.tokens("a vexillum here"))).isTrue();
            assertThat(enabled.matches(TextMatchNormalizer.tokens("mày là ĐỒ NGU"))).isTrue();

            patch(SETTINGS, Map.of("hiddenWordsSystemEnabled", false), account);

            PhraseMatcher disabled = hiddenWordService.matcherFor(account.userId());
            assertThat(disabled.matches(TextMatchNormalizer.tokens("a vexillum here"))).isFalse();
            assertThat(disabled.matches(TextMatchNormalizer.tokens("mày là ĐỒ NGU"))).isTrue();
        } finally {
            hiddenWordRepository.delete(systemWord);
            hiddenWordService.refreshSystemList();
        }
    }

    private void fillList(UUID userId, int entries) {
        List<UserHiddenWord> rows = new ArrayList<>();
        for (int i = 0; i < entries; i++) {
            String phrase = "filler " + i;
            rows.add(
                    UserHiddenWord.builder()
                            .userId(userId)
                            .phrase(phrase)
                            .normalizedPhrase(TextMatchNormalizer.normalizePhrase(phrase))
                            .build());
        }
        userHiddenWordRepository.saveAll(rows);
    }

    private static String entryId(ResponseEntity<Map> created) {
        return (String) ((Map<?, ?>) created.getBody().get("data")).get("id");
    }

    private Account newAccount(String tag) {
        String email = tag + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String username = tag + UUID.randomUUID().toString().substring(0, 6);
        post(
                "/api/v1/auth/register",
                Map.of("username", username, "email", email, "password", TEST_PASSWORD),
                null);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String verToken = tokenService.createEmailVerificationToken(userId);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + verToken, Map.class);
        ResponseEntity<Map> login =
                post(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD),
                        null);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        return new Account(userId, (String) data.get("accessToken"));
    }

    private ResponseEntity<Map> get(String path, Account account) {
        return exchange(path, HttpMethod.GET, null, account);
    }

    private ResponseEntity<Map> post(String path, Object body, Account account) {
        return exchange(path, HttpMethod.POST, body, account);
    }

    private ResponseEntity<Map> patch(String path, Object body, Account account) {
        return exchange(path, HttpMethod.PATCH, body, account);
    }

    private ResponseEntity<Map> delete(String path, Account account) {
        return exchange(path, HttpMethod.DELETE, null, account);
    }

    private ResponseEntity<Map> exchange(
            String path, HttpMethod method, Object body, Account account) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (account != null) {
            headers.setBearerAuth(account.accessToken());
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private record Account(UUID userId, String accessToken) {}
}
