package com.app.modules.comment.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.security.service.TokenBlacklistService;
import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Proves every rejection path of the WebSocket handshake authentication gate, now that {@code
 * /ws/comments/**} is {@code permitAll()} at the Spring Security layer and {@link
 * com.app.common.security.websocket.JwtHandshakeInterceptor} is the sole authentication check.
 *
 * <p>Distinct from {@link com.app.common.security.websocket.JwtHandshakeInterceptorTest}, which
 * invokes {@code beforeHandshake} directly and never traverses the security filter chain or a real
 * HTTP upgrade.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.comment.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class CommentWebSocketHandshakeRejectionIT {

    private static final Logger log =
            LoggerFactory.getLogger(CommentWebSocketHandshakeRejectionIT.class);

    private static final String JWT_SECRET = "ws-rejection-it-secret-32-chars-minimum-ok!!";
    private static final String JWT_ISSUER = "https://ws-rejection-it.test.local";
    private static final String JWT_AUDIENCE = "ws-rejection-it";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> JWT_SECRET);
        r.add("JWT_ISSUER", () -> JWT_ISSUER);
        r.add("JWT_AUDIENCE", () -> JWT_AUDIENCE);
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Rejection IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private TokenBlacklistService tokenBlacklistService;
    @Autowired private UserRepository userRepository;
    // Bad credentials are delivered through a ticket, which is the only way in now.
    @Autowired private WebSocketTicketService webSocketTicketService;

    private User activeUser() {
        String username = "ws_reject_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("WS Rejection Target")
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    /**
     * Builds a handshake URL carrying {@code tokenOrNull} inside a single-use ticket, or no
     * credential at all when null.
     *
     * <p>The handshake no longer reads a raw {@code token} parameter, so a case about a malformed,
     * wrongly-signed, expired, or blacklisted token has to deliver that token through a ticket.
     * Passing it as a bare parameter would leave it ignored and quietly turn every case here into a
     * duplicate of the no-credential one.
     */
    private String wsUrl(String tokenOrNull) {
        String base = "ws://localhost:" + port + "/ws/comments/websocket";
        return tokenOrNull == null
                ? base
                : base + "?ticket=" + webSocketTicketService.issueTicket(tokenOrNull);
    }

    private Throwable attemptConnect(String wsUrl) {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        CompletableFuture<StompSession> future =
                client.connectAsync(wsUrl, new StompSessionHandlerAdapter() {});
        return catchThrowable(() -> future.get(10, TimeUnit.SECONDS));
    }

    private static String tokenWithSecret(
            String secret, String issuer, String audience, UUID userId, Instant expiresAt) {
        // issuedAt must precede expiresAt even for an already-expired token, so anchor issuance
        // two minutes before whichever is earlier: now, or the requested expiry.
        Instant issuedAt =
                expiresAt.isBefore(Instant.now()) ? expiresAt.minusSeconds(120) : Instant.now();
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(userId.toString())
                        .claim("role", "USER")
                        .claim("jti", UUID.randomUUID().toString())
                        .issuedAt(issuedAt)
                        .notBefore(issuedAt)
                        .expiresAt(expiresAt)
                        .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void handshake_noTokenParameter_doesNotEstablishStompSession() {
        Throwable thrown = attemptConnect(wsUrl(null));

        assertThat(thrown)
                .as("a handshake with no token query parameter must not yield a STOMP session")
                .isNotNull();
        log.info("case 1 (no token) observed failure: {}", rootMessage(thrown));
    }

    @Test
    void handshake_malformedToken_doesNotEstablishStompSession() {
        Throwable thrown = attemptConnect(wsUrl("this-is-not-a-jwt"));

        assertThat(thrown)
                .as("a structurally malformed token must not yield a STOMP session")
                .isNotNull();
        log.info("case 2 (malformed token) observed failure: {}", rootMessage(thrown));
    }

    @Test
    void handshake_tokenSignedWithDifferentSecret_doesNotEstablishStompSession() {
        User user = activeUser();
        String token =
                tokenWithSecret(
                        "a-completely-different-signing-secret-32ch",
                        JWT_ISSUER,
                        JWT_AUDIENCE,
                        user.getId(),
                        Instant.now().plusSeconds(900));

        Throwable thrown = attemptConnect(wsUrl(token));

        assertThat(thrown)
                .as("a token signed with a different secret must not yield a STOMP session")
                .isNotNull();
        log.info("case 3 (wrong secret) observed failure: {}", rootMessage(thrown));
    }

    @Test
    void handshake_expiredToken_doesNotEstablishStompSession() {
        User user = activeUser();
        String token =
                tokenWithSecret(
                        JWT_SECRET,
                        JWT_ISSUER,
                        JWT_AUDIENCE,
                        user.getId(),
                        Instant.now().minusSeconds(60));

        Throwable thrown = attemptConnect(wsUrl(token));

        assertThat(thrown).as("an expired token must not yield a STOMP session").isNotNull();
        log.info("case 4 (expired token) observed failure: {}", rootMessage(thrown));
    }

    @Test
    void handshake_blacklistedJti_doesNotEstablishStompSession() {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        String jti = jwtTokenProvider.validateAndParse(token).jti();
        tokenBlacklistService.blacklist(jti, 900);

        Throwable thrown = attemptConnect(wsUrl(token));

        assertThat(thrown).as("a blacklisted jti must not yield a STOMP session").isNotNull();
        log.info("case 5 (blacklisted jti) observed failure: {}", rootMessage(thrown));
    }

    @Test
    void sockJsXhrStreamingFallback_noToken_rejectsUnauthenticatedCaller() {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String url =
                "http://localhost:" + port + "/ws/comments/000/" + sessionId + "/xhr_streaming";
        RestTemplate rest = boundedRestTemplate();

        ResponseEntity<String> response = postIgnoringErrors(rest, url);

        log.info(
                "sockjs no-token fallback observed status={} body={}",
                response.getStatusCode(),
                truncate(response.getBody()));
        assertThat(response.getStatusCode().is2xxSuccessful() && isOpenFrame(response.getBody()))
                .as(
                        "an unauthenticated SockJS xhr_streaming session-open must not yield an"
                                + " opened SockJS session (an 'o' open frame)")
                .isFalse();
    }

    /**
     * Uses the SockJS "xhr" polling transport, not "xhr_streaming".
     *
     * <p>An authenticated xhr_streaming session is a real, intentionally long-held open HTTP
     * response (SockJS keeps it open to push frames), so a blocking client reading it never
     * returns. The polling transport delivers one frame and closes, which is enough to inspect
     * response headers for both the authenticated and unauthenticated case without hanging.
     */
    @Test
    void noSessionCookie_authenticatedAndUnauthenticatedRequests_noJsessionIdCookie() {
        RestTemplate rest = boundedRestTemplate();
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);

        String authedSessionId = UUID.randomUUID().toString().substring(0, 8);
        String authedUrl =
                "http://localhost:"
                        + port
                        + "/ws/comments/000/"
                        + authedSessionId
                        + "/xhr?ticket="
                        + webSocketTicketService.issueTicket(token);
        ResponseEntity<String> authedResponse = postIgnoringErrors(rest, authedUrl);
        assertNoJsessionIdCookie(authedResponse, "authenticated");

        String anonSessionId = UUID.randomUUID().toString().substring(0, 8);
        String anonUrl = "http://localhost:" + port + "/ws/comments/000/" + anonSessionId + "/xhr";
        ResponseEntity<String> anonResponse = postIgnoringErrors(rest, anonUrl);
        assertNoJsessionIdCookie(anonResponse, "unauthenticated");
    }

    private void assertNoJsessionIdCookie(ResponseEntity<String> response, String label) {
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        log.info("{} request Set-Cookie headers: {}", label, setCookie);
        boolean hasJsessionId =
                setCookie != null && setCookie.stream().anyMatch(h -> h.contains("JSESSIONID"));
        assertThat(hasJsessionId)
                .as(label + " request to /ws/comments/** must not set a JSESSIONID cookie")
                .isFalse();
    }

    private static RestTemplate boundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        return new RestTemplate(factory);
    }

    private static ResponseEntity<String> postIgnoringErrors(RestTemplate rest, String url) {
        try {
            return rest.exchange(url, HttpMethod.POST, HttpEntity.EMPTY, String.class);
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            HttpHeaders headers = ex.getResponseHeaders();
            return new ResponseEntity<>(
                    ex.getResponseBodyAsString(),
                    headers == null ? new HttpHeaders() : headers,
                    status);
        }
    }

    private static boolean isOpenFrame(String body) {
        return body != null && body.startsWith("o");
    }

    private static String truncate(String body) {
        if (body == null) {
            return "null";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cursor = thrown;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
    }
}
