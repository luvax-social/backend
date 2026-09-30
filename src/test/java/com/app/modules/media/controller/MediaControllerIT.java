package com.app.modules.media.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.mail.service.MailService;
import com.app.modules.media.config.MediaProperties;
import com.app.modules.media.storage.ObjectStorageMetadataService;
import com.app.modules.media.validation.MediaMetadataValidator;
import com.app.testsupport.TestContainerImages;
import com.zaxxer.hikari.HikariDataSource;

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
class MediaControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "media-controller-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://media.it.local");
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
        r.add("app.media.cdn-base-url", () -> "https://cdn.it.local");
        // Presigning is local signature computation, never a call to R2, so dummy credentials
        // produce a real presigned URL offline and let the presign path be exercised here.
        r.add("app.media.r2.endpoint", () -> "https://r2.it.local");
        r.add("app.media.r2.access-key-id", () -> "it-access-key");
        r.add("app.media.r2.secret-access-key", () -> "it-secret-key");
        r.add("app.media.r2.bucket", () -> "it-bucket");
        r.add("app.media.r2.region", () -> "auto");
    }

    @MockitoBean private MailService mailService;

    // Object storage is external I/O, so stubbing it here is the same exemption MailService uses.
    @MockitoBean private ObjectStorageMetadataService objectStorageMetadataService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private DataSource dataSource;
    @Autowired private MediaMetadataValidator metadataValidator;
    @Autowired private MediaProperties mediaProperties;
    @Autowired private SystemSettingService systemSettingService;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void completeUpload_objectMissingFromStorage_returns422AndPersistsNoRow() {
        TestUser user = createUser("media_absent_owner");
        String storageKey = "users/%s/media/absent.jpg".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenReturn(Optional.empty());

        ResponseEntity<Map> response = completeUpload(user, storageKey, "image/jpeg", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("code", "MEDIA_OBJECT_NOT_UPLOADED");
        assertThat(countMediaAssets(storageKey)).isZero();
    }

    @Test
    void completeUpload_storedObjectSizeDiffersFromSubmittedMetadata_returns422AndPersistsNoRow() {
        TestUser user = createUser("media_mismatch_owner");
        String storageKey = "users/%s/media/mismatch.jpg".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenReturn(
                        Optional.of(
                                new ObjectStorageMetadataService.StoredObjectMetadata(
                                        2048L, "image/jpeg")));

        ResponseEntity<Map> response = completeUpload(user, storageKey, "image/jpeg", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("code", "MEDIA_OBJECT_METADATA_MISMATCH");
        assertThat(countMediaAssets(storageKey)).isZero();
    }

    @Test
    void completeUpload_storageUnreachable_returns503AndPersistsNoRow() {
        TestUser user = createUser("media_outage_owner");
        String storageKey = "users/%s/media/outage.jpg".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenThrow(
                        new com.app.common.exception.AppException(
                                com.app.common.enums.ApiErrorCode.MEDIA_STORAGE_UNAVAILABLE));

        ResponseEntity<Map> response = completeUpload(user, storageKey, "image/jpeg", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("code", "MEDIA_STORAGE_UNAVAILABLE");
        assertThat(countMediaAssets(storageKey)).isZero();
    }

    @Test
    void completeUpload_holdsNoDatabaseConnectionWhileTheStorageProbeRuns() {
        TestUser user = createUser("media_probe_owner");
        String storageKey = "users/%s/media/probe.jpg".formatted(user.id());
        AtomicInteger activeConnectionsDuringProbe = new AtomicInteger(-1);
        AtomicBoolean transactionActiveDuringProbe = new AtomicBoolean(true);
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenAnswer(
                        invocation -> {
                            activeConnectionsDuringProbe.set(
                                    dataSource
                                            .unwrap(HikariDataSource.class)
                                            .getHikariPoolMXBean()
                                            .getActiveConnections());
                            transactionActiveDuringProbe.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return Optional.of(
                                    new ObjectStorageMetadataService.StoredObjectMetadata(
                                            1024L, "image/jpeg"));
                        });

        ResponseEntity<Map> response = completeUpload(user, storageKey, "image/jpeg", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(activeConnectionsDuringProbe)
                .as("the storage round trip must not occupy a pooled database connection")
                .hasValue(0);
        assertThat(transactionActiveDuringProbe)
                .as("the storage probe must complete before the write transaction begins")
                .isFalse();
    }

    @Test
    void completeUpload_storedObjectMatchesSubmittedMetadata_returns201AndPersistsRow() {
        TestUser user = createUser("media_match_owner");
        String storageKey = "users/%s/media/present.jpg".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenReturn(
                        Optional.of(
                                new ObjectStorageMetadataService.StoredObjectMetadata(
                                        1024L, "image/jpeg")));

        ResponseEntity<Map> response = completeUpload(user, storageKey, "image/jpeg", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(countMediaAssets(storageKey)).isEqualTo(1);
        // OutboxService.enqueue is PROPAGATION.MANDATORY, so it throws unless a transaction is
        // already active. An outbox row therefore proves the write half still runs transactionally
        // after the probe was moved out of it, including through the package-private registrar's
        // CGLIB proxy, where a silently unapplied @Transactional would otherwise go unnoticed.
        assertThat(countOutboxEvents()).isEqualTo(1);
    }

    @Test
    void createUploadUrl_gifImage_returns200AndAGifStorageKey() {
        TestUser user = createUser("media_gif_owner");

        ResponseEntity<Map> response = createUploadUrl(user, "IMAGE", "image/gif", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(storageKeyOf(response)).endsWith(".gif");
    }

    @Test
    void createUploadUrl_quicktimeVideo_returns200AndAMovStorageKey() {
        TestUser user = createUser("media_mov_owner");

        ResponseEntity<Map> response = createUploadUrl(user, "VIDEO", "video/quicktime", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(storageKeyOf(response)).endsWith(".mov");
    }

    // Pins the decision to refuse HEIC. The CDN serves exactly what was stored and performs no
    // transcoding, so an accepted HEIC would upload cleanly and then fail to render in Chrome and
    // Firefox. Widening the allowlist to include it must break this test.
    @Test
    void createUploadUrl_heicImage_returns400() {
        TestUser user = createUser("media_heic_owner");

        ResponseEntity<Map> response = createUploadUrl(user, "IMAGE", "image/heic", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("code", "MEDIA_INVALID_METADATA");
        assertThat((String) response.getBody().get("message"))
                .isEqualTo(
                        "Unsupported image MIME type 'image/heic'. Accepted: image/gif,"
                                + " image/jpeg, image/png, image/webp");
    }

    @Test
    void createUploadUrl_heifImage_returns400() {
        TestUser user = createUser("media_heif_owner");

        ResponseEntity<Map> response = createUploadUrl(user, "IMAGE", "image/heif", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("code", "MEDIA_INVALID_METADATA");
    }

    @Test
    void createUploadUrl_typeOutsideEveryList_returns400NamingTheAcceptedVideoTypes() {
        TestUser user = createUser("media_unknown_owner");

        ResponseEntity<Map> response = createUploadUrl(user, "VIDEO", "video/x-matroska", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("message"))
                .isEqualTo(
                        "Unsupported video MIME type 'video/x-matroska'. Accepted: video/mp4,"
                                + " video/quicktime, video/webm");
    }

    @Test
    void completeUpload_gifImage_returns201AndPersistsRow() {
        TestUser user = createUser("media_gif_complete_owner");
        String storageKey = "users/%s/media/animated.gif".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenReturn(
                        Optional.of(
                                new ObjectStorageMetadataService.StoredObjectMetadata(
                                        1024L, "image/gif")));

        ResponseEntity<Map> response = completeUpload(user, storageKey, "image/gif", 1024L);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(countMediaAssets(storageKey)).isEqualTo(1);
    }

    @Test
    void completeUpload_quicktimeVideo_returns201AndPersistsRow() {
        TestUser user = createUser("media_mov_complete_owner");
        String storageKey = "users/%s/media/capture.mov".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenReturn(
                        Optional.of(
                                new ObjectStorageMetadataService.StoredObjectMetadata(
                                        2048L, "video/quicktime")));

        ResponseEntity<Map> response =
                completeUploadVideo(user, storageKey, "video/quicktime", 2048L, 12);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(countMediaAssets(storageKey)).isEqualTo(1);
    }

    @Test
    void completeUpload_videoDurationOverTheLimit_returns400AndPersistsNoRow() {
        TestUser user = createUser("media_long_video_owner");
        String storageKey = "users/%s/media/toolong.mp4".formatted(user.id());

        ResponseEntity<Map> response =
                completeUploadVideo(user, storageKey, "video/mp4", 2048L, 181);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("code", "MEDIA_INVALID_METADATA");
        assertThat((String) response.getBody().get("message"))
                .isEqualTo("Video duration must not exceed 180 seconds");
        assertThat(countMediaAssets(storageKey)).isZero();
    }

    @Test
    void completeUpload_videoDurationExactlyAtTheLimit_returns201() {
        TestUser user = createUser("media_limit_video_owner");
        String storageKey = "users/%s/media/atlimit.mp4".formatted(user.id());
        when(objectStorageMetadataService.findObjectMetadata(storageKey))
                .thenReturn(
                        Optional.of(
                                new ObjectStorageMetadataService.StoredObjectMetadata(
                                        2048L, "video/mp4")));

        ResponseEntity<Map> response =
                completeUploadVideo(user, storageKey, "video/mp4", 2048L, 180);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(countMediaAssets(storageKey)).isEqualTo(1);
    }

    @Test
    void constraints_requiresAuthentication() {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/media/constraints",
                        HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()),
                        Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // Driven from the configuration the validator reads, never from literals. A literal here would
    // pass while the endpoint and the enforcement drifted apart, which is the one failure this
    // endpoint exists to prevent.
    @Test
    void constraints_publishTheValuesTheValidatorEnforces() {
        TestUser user = createUser("media_constraints_owner");

        ResponseEntity<Map> response = getConstraints(user);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("acceptedImageMimeTypes"))
                .isEqualTo(
                        List.copyOf(
                                metadataValidator.acceptedMimeTypes(
                                        com.app.modules.media.enums.MediaType.IMAGE)));
        assertThat(data.get("acceptedVideoMimeTypes"))
                .isEqualTo(
                        List.copyOf(
                                metadataValidator.acceptedMimeTypes(
                                        com.app.modules.media.enums.MediaType.VIDEO)));
        assertThat(((Number) data.get("maxVideoDurationSeconds")).intValue())
                .isEqualTo(mediaProperties.getMaxVideoDurationSeconds());
        assertThat(((Number) data.get("maxFileSizeBytes")).longValue())
                .isEqualTo(
                        systemSettingService.getRequiredLong("max_media_size_mb") * 1024L * 1024L);
    }

    // The published list is only trustworthy if the enforcement actually refuses everything absent
    // from it, so this drives a rejected type straight from the endpoint's own answer.
    @Test
    @SuppressWarnings("unchecked")
    void constraints_everyPublishedImageTypeIsAcceptedAndAnAbsentOneIsNot() {
        TestUser user = createUser("media_constraints_agree_owner");
        Map<?, ?> data = (Map<?, ?>) getConstraints(user).getBody().get("data");
        List<String> publishedImageTypes = (List<String>) data.get("acceptedImageMimeTypes");

        for (String mimeType : publishedImageTypes) {
            assertThat(createUploadUrl(user, "IMAGE", mimeType, 1024L).getStatusCode())
                    .as("published image type %s must be accepted by presign", mimeType)
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(publishedImageTypes).doesNotContain("image/heic", "image/heif");
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> getConstraints(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        return rest.exchange(
                "/api/v1/media/constraints", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    private static String storageKeyOf(ResponseEntity<Map> response) {
        Object data = response.getBody().get("data");
        return (String) ((Map<?, ?>) data).get("storageKey");
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> createUploadUrl(
            TestUser user, String mediaType, String mimeType, long fileSize) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body =
                """
				{"mediaType":"%s","mimeType":"%s","fileSize":%d}"""
                        .formatted(mediaType, mimeType, fileSize);
        return rest.exchange(
                "/api/v1/media/upload",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> completeUploadVideo(
            TestUser user, String storageKey, String mimeType, long fileSize, int duration) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body =
                """
				{"storageKey":"%s","mediaType":"VIDEO","mimeType":"%s","fileSize":%d,\
				"width":1920,"height":1080,"duration":%d}"""
                        .formatted(storageKey, mimeType, fileSize, duration);
        return rest.exchange(
                "/api/v1/media/upload-complete",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> completeUpload(
            TestUser user, String storageKey, String mimeType, long fileSize) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body =
                """
				{"storageKey":"%s","mediaType":"IMAGE","mimeType":"%s","fileSize":%d,\
				"width":800,"height":600}"""
                        .formatted(storageKey, mimeType, fileSize);
        return rest.exchange(
                "/api/v1/media/upload-complete",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private Integer countOutboxEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
    }

    private Integer countMediaAssets(String storageKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_assets WHERE storage_key = ?",
                Integer.class,
                storageKey);
    }

    private TestUser createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST('user' AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local");
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, "USER", 0));
    }
}
