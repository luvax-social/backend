package com.app.modules.media.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.media.entity.MediaAsset;
import com.app.modules.media.enums.MediaType;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class MediaAssetRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private EntityManager entityManager;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void insert_usesDatabaseGeneratedPrimaryKeyAndPersistsMetadata() {
        UUID userId = insertUser("media_owner", "media-owner@example.com");

        MediaAsset saved =
                mediaAssetRepository.insert(
                        MediaAsset.builder()
                                .userId(userId)
                                .storageKey("users/%s/media/image.jpg".formatted(userId))
                                .cdnUrl("https://cdn.example.com/users/media/image.jpg")
                                .mediaType(MediaType.IMAGE)
                                .mimeType("image/jpeg")
                                .fileSize(1024L)
                                .width(800)
                                .height(600)
                                .duration(null)
                                .blurhash("blur")
                                .build());
        entityManager.clear();

        MediaAsset persisted = mediaAssetRepository.findById(saved.getId()).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(persisted.getUserId()).isEqualTo(userId);
        assertThat(persisted.getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void insert_duplicateStorageKeyFailsAtDatabaseLayer() {
        UUID userId = insertUser("media_duplicate", "media-duplicate@example.com");
        MediaAsset first = asset(userId, "users/%s/media/duplicate.jpg".formatted(userId));
        mediaAssetRepository.insert(first);

        assertThatThrownBy(() -> mediaAssetRepository.insert(asset(userId, first.getStorageKey())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(String username, String email) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users (username, email, role, status)
						VALUES (:username, :email, 'user', 'active')
						RETURNING id
						""")
                .param("username", username)
                .param("email", email)
                .query(UUID.class)
                .single();
    }

    private MediaAsset asset(UUID userId, String storageKey) {
        return MediaAsset.builder()
                .userId(userId)
                .storageKey(storageKey)
                .cdnUrl("https://cdn.example.com/" + storageKey)
                .mediaType(MediaType.IMAGE)
                .mimeType("image/jpeg")
                .fileSize(1024L)
                .width(800)
                .height(600)
                .build();
    }
}
