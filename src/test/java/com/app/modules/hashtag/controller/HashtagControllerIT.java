package com.app.modules.hashtag.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.hashtag.search.HashtagDocument;
import com.app.modules.hashtag.search.HashtagSearchRepository;
import com.app.modules.mail.service.MailService;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HashtagControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(DockerImageName.parse(TestContainerImages.ELASTICSEARCH))
                    .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("app.elasticsearch.uris", () -> "http://" + elasticsearch.getHttpHostAddress());
        r.add("JWT_SECRET", () -> "hashtag-controller-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://hashtag.it.local");
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
        r.add("app.outbox.publisher.enabled", () -> false);
        // Disable the startup seed runner so the index begins empty and each test owns its docs.
        r.add("app.hashtag.seed.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private HashtagSearchRepository hashtagSearchRepository;
    @Autowired private ElasticsearchOperations elasticsearchOperations;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM post_hashtags");
        jdbcTemplate.update("DELETE FROM hashtag_trending");
        jdbcTemplate.update("DELETE FROM hashtags");
        // ES cleanup is guarded: the fallback test stops the container irreversibly, so touching
        // Elasticsearch here would hang/fail once it is down. Only clear while it is still running.
        if (elasticsearch.isRunning()) {
            try {
                IndexOperations ops = elasticsearchOperations.indexOps(HashtagDocument.class);
                if (ops.exists()) {
                    ops.delete();
                }
            } catch (RuntimeException ignored) {
                // Best-effort index teardown; never fail a test on cleanup.
            }
        }
    }

    @Test
    @Order(1)
    void search_missingQueryParam_returnsBadRequest() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/hashtags/search", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(2)
    void trending_noSnapshot_returnsEmptyPage() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/hashtags/trending", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(true);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        List<?> content = (List<?>) data.get("content");
        assertThat(content).isEmpty();
    }

    @Test
    @Order(3)
    void search_indexedHashtag_returnsMatchViaElasticsearch() {
        ensureIndexExists();
        hashtagSearchRepository.saveAll(
                List.of(document("spring", 5), document("springboot", 12), document("java", 8)));
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();

        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/hashtags/search?q=spr", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = contentOf(response);
        assertThat(content).isNotEmpty();
        assertThat(content.stream().map(m -> (String) m.get("name")))
                .anyMatch(name -> name.startsWith("spr"));
    }

    @Test
    @Order(4)
    void search_cursorPagination_secondPageDisjointFromFirst() {
        ensureIndexExists();
        List<HashtagDocument> docs =
                IntStream.range(0, 25).mapToObj(i -> document("cursortag" + i, i)).toList();
        hashtagSearchRepository.saveAll(docs);
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();

        ResponseEntity<Map> page1 =
                rest.getForEntity("/api/v1/hashtags/search?q=cursor&limit=10", Map.class);
        assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> page1Content = contentOf(page1);
        assertThat(page1Content).hasSize(10);
        Map<?, ?> page1PageInfo = pageInfoOf(page1);
        assertThat(page1PageInfo.get("hasNextPage")).isEqualTo(true);
        String endCursor = (String) page1PageInfo.get("endCursor");
        assertThat(endCursor).isNotBlank();

        ResponseEntity<Map> page2 =
                rest.getForEntity(
                        "/api/v1/hashtags/search?q=cursor&limit=10&cursor=" + endCursor, Map.class);
        assertThat(page2.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> page2Content = contentOf(page2);
        assertThat(page2Content).isNotEmpty();

        java.util.Set<String> page1Names =
                page1Content.stream().map(m -> (String) m.get("name")).collect(Collectors.toSet());
        java.util.Set<String> page2Names =
                page2Content.stream().map(m -> (String) m.get("name")).collect(Collectors.toSet());
        // An empty reference collection causes doesNotContainAnyElementsOf to trivially pass,
        // masking pagination regressions — guard both sets before asserting disjointness.
        assertThat(page1Names).isNotEmpty();
        assertThat(page2Names).isNotEmpty();
        assertThat(page2Names).doesNotContainAnyElementsOf(page1Names);
    }

    @Test
    @Order(5)
    void search_elasticsearchDown_fallsBackToPostgresTrgm() {
        jdbcTemplate.update(
                "INSERT INTO hashtags (name) VALUES (?) ON CONFLICT (name) DO NOTHING",
                "integration");

        elasticsearch.stop();

        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/hashtags/search?q=integ", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = contentOf(response);
        assertThat(content.stream().map(m -> (String) m.get("name")))
                .anyMatch(name -> name.contains("integ"));
    }

    @Test
    @Order(6)
    void trending_oversizedSize_returnsBadRequest() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/hashtags/trending?size=2000", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(7)
    void trending_negativePage_returnsBadRequest() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/hashtags/trending?page=-1", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void ensureIndexExists() {
        IndexOperations ops = elasticsearchOperations.indexOps(HashtagDocument.class);
        if (!ops.exists()) {
            ops.createWithMapping();
        }
    }

    private static HashtagDocument document(String name, int postCount) {
        return HashtagDocument.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .postCount(postCount)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> contentOf(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<?, ?>>) data.get("content");
    }

    private static Map<?, ?> pageInfoOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (Map<?, ?>) data.get("pageInfo");
    }
}
