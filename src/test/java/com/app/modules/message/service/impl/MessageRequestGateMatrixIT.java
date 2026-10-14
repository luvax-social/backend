package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.user.UserPrincipal;
import com.app.common.security.websocket.JwtHandshakeInterceptor;
import com.app.modules.message.live.MessageInboxAuthInterceptor;
import com.app.modules.message.live.MessageWebSocketAuthInterceptor;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.MessageUserRepository;
import com.app.testsupport.TestContainerImages;

/**
 * What each party to a message request may do with it, one cell per party and operation.
 *
 * <p>The live subscription checks are the ones the WebSocket channel runs, built over the real
 * repositories: the thread topic, and the per-user inbox topic subscribed as the account's own
 * inbox and as the other party's inbox.
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
class MessageRequestGateMatrixIT {

    enum Actor {
        RECIPIENT_OF_PENDING,
        RECIPIENT_OF_HIDDEN_PENDING,
        RECIPIENT_OF_DECLINED,
        REQUESTER_OF_DECLINED,
        STRANGER
    }

    enum Operation {
        LIST_FOLDERS,
        DETAIL,
        HISTORY,
        SEND,
        MARK_READ,
        UNREAD_COUNT,
        SUBSCRIBE_THREAD,
        SUBSCRIBE_OWN_INBOX,
        SUBSCRIBE_OTHER_INBOX
    }

    private static final Map<String, Scenario> SCENARIOS = new HashMap<>();
    private static TestUser stranger;

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
        registry.add("JWT_SECRET", () -> "message-matrix-it-secret-32-chars!!!!!!!");
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
    @Autowired private ConversationParticipantRepository participantRepository;
    @Autowired private MessageUserRepository userRepository;

    private record TestUser(UUID id, String token) {}

    private record Scenario(UUID conversationId, TestUser requester, TestUser recipient) {}

    static Stream<Arguments> cells() {
        return Stream.of(
                // list folders the conversation appears in, per actor
                cell(Actor.RECIPIENT_OF_PENDING, Operation.LIST_FOLDERS, "requests"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.LIST_FOLDERS, "hidden_requests"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.LIST_FOLDERS, "none"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.LIST_FOLDERS, "primary"),
                cell(Actor.STRANGER, Operation.LIST_FOLDERS, "none"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.DETAIL, "200"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.DETAIL, "200"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.DETAIL, "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.DETAIL, "200"),
                cell(Actor.STRANGER, Operation.DETAIL, "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.HISTORY, "200"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.HISTORY, "200"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.HISTORY, "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.HISTORY, "200"),
                cell(Actor.STRANGER, Operation.HISTORY, "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.SEND, "201"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.SEND, "201"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.SEND, "403 CONVERSATION_FORBIDDEN"),
                cell(
                        Actor.REQUESTER_OF_DECLINED,
                        Operation.SEND,
                        "409 MESSAGE_REQUEST_AWAITING_ACCEPTANCE"),
                cell(Actor.STRANGER, Operation.SEND, "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.MARK_READ, "200"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.MARK_READ, "200"),
                cell(
                        Actor.RECIPIENT_OF_DECLINED,
                        Operation.MARK_READ,
                        "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.MARK_READ, "200"),
                cell(Actor.STRANGER, Operation.MARK_READ, "403 CONVERSATION_FORBIDDEN"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.UNREAD_COUNT, "unread=0 requests=1"),
                cell(
                        Actor.RECIPIENT_OF_HIDDEN_PENDING,
                        Operation.UNREAD_COUNT,
                        "unread=0 requests=0"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.UNREAD_COUNT, "unread=0 requests=0"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.UNREAD_COUNT, "unread=0 requests=0"),
                cell(Actor.STRANGER, Operation.UNREAD_COUNT, "unread=0 requests=0"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.SUBSCRIBE_THREAD, "allowed"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.SUBSCRIBE_THREAD, "allowed"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.SUBSCRIBE_THREAD, "rejected"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.SUBSCRIBE_THREAD, "allowed"),
                cell(Actor.STRANGER, Operation.SUBSCRIBE_THREAD, "rejected"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.SUBSCRIBE_OWN_INBOX, "allowed"),
                cell(Actor.RECIPIENT_OF_HIDDEN_PENDING, Operation.SUBSCRIBE_OWN_INBOX, "allowed"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.SUBSCRIBE_OWN_INBOX, "allowed"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.SUBSCRIBE_OWN_INBOX, "allowed"),
                cell(Actor.STRANGER, Operation.SUBSCRIBE_OWN_INBOX, "allowed"),
                cell(Actor.RECIPIENT_OF_PENDING, Operation.SUBSCRIBE_OTHER_INBOX, "rejected"),
                cell(
                        Actor.RECIPIENT_OF_HIDDEN_PENDING,
                        Operation.SUBSCRIBE_OTHER_INBOX,
                        "rejected"),
                cell(Actor.RECIPIENT_OF_DECLINED, Operation.SUBSCRIBE_OTHER_INBOX, "rejected"),
                cell(Actor.REQUESTER_OF_DECLINED, Operation.SUBSCRIBE_OTHER_INBOX, "rejected"),
                cell(Actor.STRANGER, Operation.SUBSCRIBE_OTHER_INBOX, "rejected"));
    }

    private static Arguments cell(Actor actor, Operation operation, String expected) {
        return Arguments.of(actor, operation, expected);
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("cells")
    void gate(Actor actor, Operation operation, String expected) {
        Scenario scenario = scenarioFor(actor, operation);
        TestUser caller = callerFor(actor, scenario);

        assertThat(observe(operation, caller, scenario)).isEqualTo(expected);
    }

    private String observe(Operation operation, TestUser caller, Scenario scenario) {
        UUID conversationId = scenario.conversationId();
        String base = "/api/v1/conversations/" + conversationId;
        return switch (operation) {
            case LIST_FOLDERS -> {
                List<String> found =
                        List.of("primary", "requests", "hidden_requests").stream()
                                .filter(
                                        folder ->
                                                folderIds(caller, folder).contains(conversationId))
                                .toList();
                yield found.isEmpty() ? "none" : String.join(",", found);
            }
            case DETAIL -> outcome(call(HttpMethod.GET, caller, base));
            case HISTORY -> outcome(call(HttpMethod.GET, caller, base + "/messages"));
            case SEND -> outcome(send(caller, conversationId, "a reply"));
            case MARK_READ -> outcome(call(HttpMethod.POST, caller, base + "/read"));
            case UNREAD_COUNT -> {
                Map<?, ?> data =
                        dataOf(call(HttpMethod.GET, caller, "/api/v1/conversations/unread-count"));
                yield "unread=" + data.get("unreadCount") + " requests=" + data.get("requestCount");
            }
            case SUBSCRIBE_THREAD -> subscribe(caller, conversationId);
            case SUBSCRIBE_OWN_INBOX -> subscribeInbox(caller, caller.id());
            case SUBSCRIBE_OTHER_INBOX -> subscribeInbox(caller, counterpartOf(caller, scenario));
        };
    }

    private static String outcome(ResponseEntity<Map> response) {
        Object code = response.getBody() == null ? null : response.getBody().get("code");
        boolean error = !response.getStatusCode().is2xxSuccessful();
        return response.getStatusCode().value() + (error && code != null ? " " + code : "");
    }

    private String subscribe(TestUser caller, UUID conversationId) {
        MessageWebSocketAuthInterceptor interceptor =
                new MessageWebSocketAuthInterceptor(participantRepository, userRepository);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/conversations." + conversationId + ".messages");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(
                JwtHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new UserPrincipal(caller.id(), null, "user", "ACTIVE"));
        accessor.setSessionAttributes(attributes);
        Message<?> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        try {
            interceptor.preSend(frame, null);
            return "allowed";
        } catch (MessageDeliveryException ex) {
            return "rejected";
        }
    }

    // The other party's inbox is the one an actor has a reason to want: the requester's for a
    // recipient, the recipient's for the requester and for an outsider.
    private static UUID counterpartOf(TestUser caller, Scenario scenario) {
        return caller.id().equals(scenario.recipient().id())
                ? scenario.requester().id()
                : scenario.recipient().id();
    }

    private String subscribeInbox(TestUser caller, UUID inboxOwnerId) {
        MessageInboxAuthInterceptor interceptor = new MessageInboxAuthInterceptor(userRepository);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/inbox." + inboxOwnerId);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(
                JwtHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new UserPrincipal(caller.id(), null, "user", "ACTIVE"));
        accessor.setSessionAttributes(attributes);
        Message<?> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        try {
            interceptor.preSend(frame, null);
            return "allowed";
        } catch (MessageDeliveryException ex) {
            return "rejected";
        }
    }

    // Sending as the recipient of a pending request accepts it, so those two cells get their own
    // conversation instead of changing the one the read-only cells share.
    private Scenario scenarioFor(Actor actor, Operation operation) {
        String kind =
                switch (actor) {
                    case RECIPIENT_OF_PENDING -> "pending";
                    case RECIPIENT_OF_HIDDEN_PENDING -> "hidden";
                    default -> "declined";
                };
        String key = kind + (operation == Operation.SEND ? "-send" : "");
        synchronized (SCENARIOS) {
            return SCENARIOS.computeIfAbsent(key, ignored -> createScenario(kind));
        }
    }

    private TestUser callerFor(Actor actor, Scenario scenario) {
        return switch (actor) {
            case REQUESTER_OF_DECLINED -> scenario.requester();
            case STRANGER -> strangerUser();
            default -> scenario.recipient();
        };
    }

    private TestUser strangerUser() {
        synchronized (SCENARIOS) {
            if (stranger == null) {
                stranger = registerUser("gm_x");
            }
            return stranger;
        }
    }

    private Scenario createScenario(String kind) {
        TestUser requester = registerUser("gm_a");
        TestUser recipient = registerUser("gm_b");
        if (kind.equals("hidden")) {
            jdbc.update(
                    "INSERT INTO user_hidden_words(user_id, phrase, normalized_phrase)"
                            + " VALUES (?, 'zorblax', 'zorblax')",
                    recipient.id());
        }
        UUID conversationId = createDirectOk(requester, recipient.id());
        String text = kind.equals("hidden") ? "you are a Zorblax" : "hello";
        assertThat(send(requester, conversationId, text).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        if (kind.equals("declined")) {
            assertThat(
                            call(
                                            HttpMethod.DELETE,
                                            recipient,
                                            "/api/v1/conversations/" + conversationId + "/request")
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
        return new Scenario(conversationId, requester, recipient);
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

    private List<UUID> folderIds(TestUser actor, String folder) {
        ResponseEntity<Map> response =
                call(HttpMethod.GET, actor, "/api/v1/conversations?limit=100&folder=" + folder);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = (List<Map<?, ?>>) dataOf(response).get("content");
        return content.stream().map(row -> UUID.fromString((String) row.get("id"))).toList();
    }

    private static HttpHeaders authHeaders(TestUser actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(actor.token());
        return headers;
    }
}
