package com.app.modules.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class NotificationControllerIT {

    private static final String TEST_JWT_SECRET = "notification-it-secret-32-chars-minimum-length!";
    private static final String TEST_JWT_ISSUER = "https://notification-it.test.local";
    private static final String TEST_JWT_AUDIENCE = "App";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
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
        r.add("MAIL_FROM_NAME", () -> "App Notification IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userA =
                userRepository.save(
                        activeUser("notif_a_" + UUID.randomUUID().toString().substring(0, 8)));
        userB =
                userRepository.save(
                        activeUser("notif_b_" + UUID.randomUUID().toString().substring(0, 8)));
    }

    @Test
    void listNotifications_noNotifications_returnsEmptyPage() {
        ResponseEntity<Map> response = getWithToken("/api/v1/notifications", userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> items = content(response);
        assertThat(items == null || items.isEmpty()).isTrue();
        assertThat(pageInfo(response).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    void listNotifications_withoutJwt_returns401() {
        ResponseEntity<Map> response =
                rest.exchange("/api/v1/notifications", HttpMethod.GET, HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listNotifications_cursorPagination_returnsCorrectPages() {
        for (int i = 0; i < 25; i++) {
            seedFollow(userA.getId(), userB.getId());
        }

        ResponseEntity<Map> page1 = getWithToken("/api/v1/notifications?limit=10", userA);
        assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(page1)).hasSize(10);
        assertThat(pageInfo(page1).get("hasNextPage")).isEqualTo(true);

        String cursor1 = (String) pageInfo(page1).get("endCursor");
        ResponseEntity<Map> page2 =
                getWithToken("/api/v1/notifications?limit=10&cursor=" + cursor1, userA);
        assertThat(page2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(page2)).hasSize(10);
        assertThat(pageInfo(page2).get("hasNextPage")).isEqualTo(true);

        String cursor2 = (String) pageInfo(page2).get("endCursor");
        ResponseEntity<Map> page3 =
                getWithToken("/api/v1/notifications?limit=10&cursor=" + cursor2, userA);
        assertThat(page3.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(page3)).hasSize(5);
        assertThat(pageInfo(page3).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    void listNotifications_limitAboveMax_returnsBadRequest() {
        ResponseEntity<Map> response = getWithToken("/api/v1/notifications?limit=999", userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listNotifications_commentPostNotification_includesPostId() {
        UUID postId = UUID.randomUUID();
        Notification commentNotification =
                notificationRepository.save(
                        Notification.builder()
                                .recipientId(userA.getId())
                                .actorId(userB.getId())
                                .type(NotificationType.COMMENT_POST)
                                .category(NotificationType.COMMENT_POST.category())
                                .entityType("comment")
                                .entityId(UUID.randomUUID())
                                .postId(postId)
                                .build());

        ResponseEntity<Map> response = getWithToken("/api/v1/notifications", userA);

        List<?> items = content(response);
        Map<?, ?> item =
                items.stream()
                        .map(i -> (Map<?, ?>) i)
                        .filter(i -> i.get("id").equals(commentNotification.getId().toString()))
                        .findFirst()
                        .orElseThrow();
        assertThat(item.get("postId")).isEqualTo(postId.toString());
    }

    @Test
    void listNotifications_followNotification_postIdIsNull() {
        seedFollow(userA.getId(), userB.getId());

        ResponseEntity<Map> response = getWithToken("/api/v1/notifications", userA);

        List<?> items = content(response);
        Map<?, ?> item = ((Map<?, ?>) items.get(0));
        assertThat(item.get("postId")).isNull();
    }

    @Test
    void markAsRead_ownNotification_returns200() {
        Notification n = seedFollow(userA.getId(), userB.getId());

        ResponseEntity<Map> response =
                patchWithToken("/api/v1/notifications/" + n.getId() + "/read", userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Notification reloaded = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(reloaded.getReadAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listNotifications_readAndUnread_reflectsIsReadInResponseBody() {
        Notification read = seedFollow(userA.getId(), userB.getId());
        Notification unread = seedFollow(userA.getId(), userB.getId());
        patchWithToken("/api/v1/notifications/" + read.getId() + "/read", userA);

        ResponseEntity<Map> response = getWithToken("/api/v1/notifications", userA);

        List<?> items = content(response);
        Map<?, ?> readItem =
                items.stream()
                        .map(i -> (Map<?, ?>) i)
                        .filter(i -> i.get("id").equals(read.getId().toString()))
                        .findFirst()
                        .orElseThrow();
        Map<?, ?> unreadItem =
                items.stream()
                        .map(i -> (Map<?, ?>) i)
                        .filter(i -> i.get("id").equals(unread.getId().toString()))
                        .findFirst()
                        .orElseThrow();
        assertThat(readItem.get("isRead")).isEqualTo(true);
        assertThat(unreadItem.get("isRead")).isEqualTo(false);
    }

    @Test
    void markAsRead_otherUsersNotification_returns403() {
        Notification n = seedFollow(userB.getId(), userA.getId());

        ResponseEntity<Map> response =
                patchWithToken("/api/v1/notifications/" + n.getId() + "/read", userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void markAsRead_withoutJwt_returns401() {
        Notification n = seedFollow(userA.getId(), userB.getId());

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/notifications/" + n.getId() + "/read",
                        HttpMethod.PATCH,
                        HttpEntity.EMPTY,
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void markAllAsRead_otherUserNotifications_notAffected() {
        for (int i = 0; i < 3; i++) {
            seedFollow(userA.getId(), userB.getId());
        }
        for (int i = 0; i < 2; i++) {
            seedFollow(userB.getId(), userA.getId());
        }

        ResponseEntity<Map> response = patchWithToken("/api/v1/notifications/read-all", userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notificationRepository.countByRecipientIdAndIsReadFalse(userA.getId()))
                .isEqualTo(0);
        assertThat(notificationRepository.countByRecipientIdAndIsReadFalse(userB.getId()))
                .isEqualTo(2);
    }

    @Test
    void getUnreadCount_mixedReadStates_returnsUnreadOnly() {
        notificationRepository.save(
                Notification.builder()
                        .recipientId(userA.getId())
                        .actorId(userB.getId())
                        .type(NotificationType.FOLLOW)
                        .category(NotificationType.FOLLOW.category())
                        .readAt(OffsetDateTime.now())
                        .build());
        seedFollow(userA.getId(), userB.getId());
        seedFollow(userA.getId(), userB.getId());

        ResponseEntity<Map> response = getWithToken("/api/v1/notifications/unread-count", userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(((Number) data.get("unreadCount")).longValue()).isEqualTo(2);
    }

    private ResponseEntity<Map> getWithToken(String path, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor(user));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> patchWithToken(String path, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor(user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<?> content(ResponseEntity<Map> resp) {
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        return (List<?>) data.get("content");
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> pageInfo(ResponseEntity<Map> resp) {
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        return (Map<?, ?>) data.get("pageInfo");
    }

    private Notification seedFollow(UUID recipientId, UUID actorId) {
        return notificationRepository.save(
                Notification.builder()
                        .recipientId(recipientId)
                        .actorId(actorId)
                        .type(NotificationType.FOLLOW)
                        .category(NotificationType.FOLLOW.category())
                        .build());
    }

    private String jwtFor(User user) {
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getRole().name(), 0);
    }

    private static User activeUser(String username) {
        return User.builder()
                .email(username + "@test.local")
                .username(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .isPrivate(false)
                .isVerified(true)
                .build();
    }
}
