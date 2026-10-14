package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.messaging.DomainEventMessageParser;
import com.app.modules.message.live.MessageLiveFanoutConsumer;
import com.app.modules.message.live.MessageLiveServerQueueInitializer;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.json.JsonMapper;

/**
 * A declined request must be indistinguishable from a pending one for the account that sent it:
 * every response it can observe is compared before and after the recipient deletes the request,
 * ignoring only the {@code timestamp} the response envelope adds.
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
@AutoConfigureTestRestTemplate
class MessageRequestStealthIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("TURNSTILE_AUTH_ENABLED", () -> false);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "message-stealth-it-secret-32-chars!!!!!!");
        registry.add("JWT_ISSUER", () -> "https://message.it.local");
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
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    private record TestUser(UUID id, String token) {}

    @Test
    void declinedRequest_everySenderVisibleResponseMatchesThePendingOne() throws Exception {
        TestUser alice = registerUser("st_a");
        TestUser bob = registerUser("st_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        assertThat(send(alice, conversationId, "hello bob").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        List<String> whilePending = senderView(alice, conversationId);
        ResponseEntity<Map> declined =
                call(
                        HttpMethod.DELETE,
                        bob,
                        "/api/v1/conversations/" + conversationId + "/request");
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<String> afterDecline = senderView(alice, conversationId);

        assertThat(afterDecline).hasSameSizeAs(whilePending);
        for (int i = 0; i < whilePending.size(); i++) {
            assertThat(afterDecline.get(i)).isEqualTo(whilePending.get(i));
        }
    }

    @Test
    void declinedRequest_theResolutionEventIsNeverPushedToTheThreadTopic() {
        TestUser alice = registerUser("st_a");
        TestUser bob = registerUser("st_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        send(alice, conversationId, "hello bob");
        call(HttpMethod.DELETE, bob, "/api/v1/conversations/" + conversationId + "/request");
        String payload =
                jdbc.queryForObject(
                        "SELECT payload::text FROM outbox_events"
                                + " WHERE event_type = 'message.request.resolved.v1'"
                                + " AND aggregate_id = ?",
                        String.class,
                        conversationId);
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MessageLiveFanoutConsumer consumer =
                new MessageLiveFanoutConsumer(
                        new DomainEventMessageParser(),
                        template,
                        mock(MessageLiveServerQueueInitializer.class));

        consumer.consume(new Message(payload.getBytes(StandardCharsets.UTF_8)));

        verifyNoInteractions(template);
    }

    // The sender's list row, detail, history page, refused send and unread count, as the exact text
    // of each response with the envelope timestamp removed.
    private List<String> senderView(TestUser sender, UUID conversationId) throws Exception {
        List<String> view = new ArrayList<>();
        ResponseEntity<Map> list =
                call(HttpMethod.GET, sender, "/api/v1/conversations?limit=100&folder=primary");
        List<Map<?, ?>> rows = (List<Map<?, ?>>) dataOf(list).get("content");
        view.add(
                normalise(
                        rows.stream()
                                .filter(row -> conversationId.toString().equals(row.get("id")))
                                .findFirst()
                                .orElse(Map.of("missing", true))));
        view.add(
                responseText(
                        call(HttpMethod.GET, sender, "/api/v1/conversations/" + conversationId)));
        view.add(
                responseText(
                        call(
                                HttpMethod.GET,
                                sender,
                                "/api/v1/conversations/" + conversationId + "/messages")));
        view.add(responseText(send(sender, conversationId, "are you there")));
        view.add(responseText(call(HttpMethod.GET, sender, "/api/v1/conversations/unread-count")));
        return view;
    }

    private static String responseText(ResponseEntity<Map> response) throws Exception {
        return response.getStatusCode().value() + " " + normalise(response.getBody());
    }

    private static String normalise(Object body) throws Exception {
        return JSON.writeValueAsString(withoutTimestamps(body));
    }

    private static Object withoutTimestamps(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach(
                    (key, value) -> {
                        if (!"timestamp".equals(key)) {
                            copy.put(key, withoutTimestamps(value));
                        }
                    });
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(MessageRequestStealthIT::withoutTimestamps).toList();
        }
        return node;
    }

    private TestUser registerUser(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 6);
        String email = username + "@test.local";
        String password = "S3cur3P@ssword!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> registration =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "username", username,
                                        "email", email,
                                        "password", password,
                                        "displayName", username),
                                headers),
                        Map.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id =
                jdbc.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        jdbc.update("UPDATE user_credentials SET email_verified = TRUE WHERE user_id = ?", id);
        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new TestUser(
                id, (String) ((Map<?, ?>) login.getBody().get("data")).get("accessToken"));
    }

    private UUID createDirectOk(TestUser actor, UUID targetId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("targetUserId", targetId), authHeaders(actor)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) dataOf(response).get("id"));
    }

    private static Map<?, ?> dataOf(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private ResponseEntity<Map> send(TestUser actor, UUID conversationId, String content) {
        return rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("messageType", "text", "content", content), authHeaders(actor)),
                Map.class);
    }

    private ResponseEntity<Map> call(HttpMethod method, TestUser actor, String path) {
        return rest.exchange(path, method, new HttpEntity<>(authHeaders(actor)), Map.class);
    }

    private static HttpHeaders authHeaders(TestUser actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(actor.token());
        return headers;
    }
}
