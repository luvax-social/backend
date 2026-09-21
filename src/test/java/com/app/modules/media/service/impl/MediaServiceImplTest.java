package com.app.modules.media.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.user.UserPrincipal;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.media.config.MediaProperties;
import com.app.modules.media.dto.request.MediaUploadCompleteRequest;
import com.app.modules.media.dto.request.MediaUploadUrlRequest;
import com.app.modules.media.dto.response.MediaAssetResponse;
import com.app.modules.media.dto.response.MediaUploadUrlResponse;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.media.enums.MediaType;
import com.app.modules.media.mapper.MediaAssetMapper;
import com.app.modules.media.storage.MediaStorageKeyGenerator;
import com.app.modules.media.storage.ObjectStorageMetadataService;
import com.app.modules.media.storage.ObjectStoragePresignService;
import com.app.modules.media.validation.MediaMetadataValidator;

@ExtendWith(MockitoExtension.class)
class MediaServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID MEDIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000456");
    private static final String VALID_STORAGE_KEY = "users/%s/media/image.jpg".formatted(USER_ID);

    @Mock private SystemSettingService systemSettingService;
    @Mock private MediaStorageKeyGenerator storageKeyGenerator;
    @Mock private ObjectStoragePresignService objectStoragePresignService;
    @Mock private ObjectStorageMetadataService objectStorageMetadataService;
    @Mock private MediaAssetRegistrar mediaAssetRegistrar;

    private MediaServiceImpl service;

    @BeforeEach
    void setUp() {
        MediaProperties mediaProperties = new MediaProperties();
        mediaProperties.setCdnBaseUrl("https://media.luvax.online");
        service =
                new MediaServiceImpl(
                        new MediaMetadataValidator(mediaProperties),
                        systemSettingService,
                        mediaProperties,
                        storageKeyGenerator,
                        objectStoragePresignService,
                        objectStorageMetadataService,
                        mediaAssetRegistrar);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new UserPrincipal(USER_ID, "user@example.com", "USER", "ACTIVE"),
                                "token",
                                java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUploadUrl_validRequest_returnsStorageKeyAndPresignedUrl() throws Exception {
        MediaUploadUrlRequest request =
                new MediaUploadUrlRequest(MediaType.IMAGE, "IMAGE/JPEG", 1024L);
        String storageKey = "users/%s/media/file.jpg".formatted(USER_ID);
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        when(storageKeyGenerator.generate(USER_ID, MediaType.IMAGE, "image/jpeg"))
                .thenReturn(storageKey);
        when(objectStoragePresignService.presignPutObject(storageKey, "image/jpeg", 1024L))
                .thenReturn(
                        new ObjectStoragePresignService.PresignedUpload(
                                new java.net.URL("https://r2.example.com/bucket/file.jpg"),
                                "PUT",
                                Map.of("content-type", "image/jpeg"),
                                java.time.Instant.parse("2026-05-27T12:00:00Z")));

        MediaUploadUrlResponse response = service.createUploadUrl(request);

        assertThat(response.storageKey()).isEqualTo(storageKey);
        assertThat(response.method()).isEqualTo("PUT");
        assertThat(response.requiredHeaders()).containsEntry("content-type", "image/jpeg");
        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createUploadUrl_rejectsOversizedFileBeforePresigning() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(1L);
        MediaUploadUrlRequest request =
                new MediaUploadUrlRequest(MediaType.IMAGE, "image/jpeg", 2L * 1024L * 1024L);

        assertThatThrownBy(() -> service.createUploadUrl(request)).isInstanceOf(AppException.class);

        verify(objectStoragePresignService, never())
                .presignPutObject(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void completeUpload_verifiedObjectIsHandedToTheRegistrarWithADerivedCdnUrl() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        stubStoredObject(1024L, "image/jpeg");
        when(mediaAssetRegistrar.register(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            MediaAsset draft = invocation.getArgument(0);
                            draft.setId(MEDIA_ID);
                            draft.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                            return new MediaAssetMapper().toResponse(draft);
                        });

        MediaAssetResponse response = service.completeUpload(imageRequest());

        ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
        verify(mediaAssetRegistrar).register(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getStorageKey()).isEqualTo(VALID_STORAGE_KEY);
        assertThat(captor.getValue().getCdnUrl())
                .startsWith("https://")
                .isEqualTo("https://media.luvax.online/" + VALID_STORAGE_KEY);
        assertThat(response.id()).isEqualTo(MEDIA_ID);
    }

    @Test
    void completeUpload_probesStorageBeforeHandingTheAssetToTheTransactionalRegistrar() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        stubStoredObject(1024L, "image/jpeg");

        service.completeUpload(imageRequest());

        // The registrar owns the only @Transactional method on this path, so a probe that happens
        // strictly before it is a probe that happens before any transaction opens.
        InOrder order = inOrder(objectStorageMetadataService, mediaAssetRegistrar);
        order.verify(objectStorageMetadataService).findObjectMetadata(VALID_STORAGE_KEY);
        order.verify(mediaAssetRegistrar).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeUpload_objectAbsentFromStorageRejectsBeforeReachingTheRegistrar() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        when(objectStorageMetadataService.findObjectMetadata(VALID_STORAGE_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeUpload(imageRequest()))
                .isInstanceOf(AppException.class)
                .extracting(throwable -> ((AppException) throwable).getErrorCode())
                .isEqualTo(ApiErrorCode.MEDIA_OBJECT_NOT_UPLOADED);

        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeUpload_storageLookupFailureRejectsBeforeReachingTheRegistrar() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        when(objectStorageMetadataService.findObjectMetadata(VALID_STORAGE_KEY))
                .thenThrow(new AppException(ApiErrorCode.MEDIA_STORAGE_UNAVAILABLE));

        assertThatThrownBy(() -> service.completeUpload(imageRequest()))
                .isInstanceOf(AppException.class)
                .extracting(throwable -> ((AppException) throwable).getErrorCode())
                .isEqualTo(ApiErrorCode.MEDIA_STORAGE_UNAVAILABLE);

        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeUpload_storedObjectSizeMismatchRejectsBeforeReachingTheRegistrar() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        stubStoredObject(2048L, "image/jpeg");

        assertThatThrownBy(() -> service.completeUpload(imageRequest()))
                .isInstanceOf(AppException.class)
                .extracting(throwable -> ((AppException) throwable).getErrorCode())
                .isEqualTo(ApiErrorCode.MEDIA_OBJECT_METADATA_MISMATCH);

        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeUpload_storedObjectContentTypeMismatchRejectsBeforeReachingTheRegistrar() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        stubStoredObject(1024L, "image/png");

        assertThatThrownBy(() -> service.completeUpload(imageRequest()))
                .isInstanceOf(AppException.class)
                .extracting(throwable -> ((AppException) throwable).getErrorCode())
                .isEqualTo(ApiErrorCode.MEDIA_OBJECT_METADATA_MISMATCH);

        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeUpload_storedObjectContentTypeComparisonIgnoresParametersAndCase() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        stubStoredObject(1024L, "IMAGE/JPEG; charset=binary");

        service.completeUpload(imageRequest());

        verify(mediaAssetRegistrar).register(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeUpload_storageIsProbedEvenForAnAlreadyRegisteredStorageKey() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        stubStoredObject(1024L, "image/jpeg");
        when(mediaAssetRegistrar.register(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AppException(ApiErrorCode.MEDIA_STORAGE_KEY_ALREADY_EXISTS));

        assertThatThrownBy(() -> service.completeUpload(imageRequest()))
                .isInstanceOf(AppException.class)
                .extracting(throwable -> ((AppException) throwable).getErrorCode())
                .isEqualTo(ApiErrorCode.MEDIA_STORAGE_KEY_ALREADY_EXISTS);

        // Accepted cost of keeping the probe out of the transaction: the duplicate check now sits
        // behind a network round trip instead of in front of it.
        verify(objectStorageMetadataService).findObjectMetadata(VALID_STORAGE_KEY);
    }

    @Test
    void completeUpload_storageKeyOutsideCurrentUserPrefixRejectsBeforeProbingStorage() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        MediaUploadCompleteRequest request =
                new MediaUploadCompleteRequest(
                        "users/00000000-0000-0000-0000-000000000999/media/image.jpg",
                        MediaType.IMAGE,
                        "image/jpeg",
                        1024L,
                        800,
                        600,
                        null,
                        "blur");

        assertThatThrownBy(() -> service.completeUpload(request)).isInstanceOf(AppException.class);

        verify(objectStorageMetadataService, never())
                .findObjectMetadata(org.mockito.ArgumentMatchers.anyString());
        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    // The invalid example used to be image/gif. GIF is an accepted image type now, so this pins
    // HEIC instead, which is deliberately refused and therefore still rejects before the probe.
    @Test
    void completeUpload_invalidMetadataRejectsBeforeProbingStorage() {
        when(systemSettingService.getRequiredLong("max_media_size_mb")).thenReturn(100L);
        MediaUploadCompleteRequest request =
                new MediaUploadCompleteRequest(
                        VALID_STORAGE_KEY,
                        MediaType.IMAGE,
                        "image/heic",
                        1024L,
                        800,
                        600,
                        null,
                        null);

        assertThatThrownBy(() -> service.completeUpload(request)).isInstanceOf(AppException.class);

        verify(objectStorageMetadataService, never())
                .findObjectMetadata(org.mockito.ArgumentMatchers.anyString());
        verify(mediaAssetRegistrar, never()).register(org.mockito.ArgumentMatchers.any());
    }

    private void stubStoredObject(long contentLength, String contentType) {
        when(objectStorageMetadataService.findObjectMetadata(VALID_STORAGE_KEY))
                .thenReturn(
                        Optional.of(
                                new ObjectStorageMetadataService.StoredObjectMetadata(
                                        contentLength, contentType)));
    }

    private MediaUploadCompleteRequest imageRequest() {
        return new MediaUploadCompleteRequest(
                VALID_STORAGE_KEY, MediaType.IMAGE, "image/jpeg", 1024L, 800, 600, null, "blur");
    }
}
