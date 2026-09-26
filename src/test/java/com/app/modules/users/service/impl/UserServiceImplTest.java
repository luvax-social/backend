package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.recommendation.service.UserEventRecorder;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.dto.request.UpdateProfileRequest;
import com.app.modules.users.dto.request.UpdateSettingsRequest;
import com.app.modules.users.dto.response.PublicUserProfileResponse;
import com.app.modules.users.dto.response.UserProfileResponse;
import com.app.modules.users.dto.response.UserSettingsResponse;
import com.app.modules.users.entity.User;
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.mapper.UserMapper;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserSettingsRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private UserSettingsRepository settingsRepository;
    @Mock private UserMapper userMapper;
    @Mock private SocialService socialService;
    @Mock private UserEventRecorder userEventRecorder;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new UserServiceImpl(
                        userRepository,
                        settingsRepository,
                        userMapper,
                        socialService,
                        userEventRecorder);
    }

    // ── getMyProfile ──────────────────────────────────────────────────────────

    @Test
    void getMyProfile_existingUser_returnsProfileResponse() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        UserProfileResponse expected = profileResponse(id);
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(expected);

        UserProfileResponse result = service.getMyProfile(id);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getMyProfile_unknownUser_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(id))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    // ── updateMyProfile ───────────────────────────────────────────────────────

    @Test
    void updateMyProfile_allNonNullFields_appliedToEntityAndSaved() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "old");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        UpdateProfileRequest req =
                new UpdateProfileRequest(
                        "newuser",
                        "New Name",
                        "bio text",
                        "https://example.com/avatar.jpg",
                        "https://example.com/banner.jpg",
                        "https://example.com",
                        false);

        service.updateMyProfile(id, req);

        assertThat(user.getUsername()).isEqualTo("newuser");
        assertThat(user.getDisplayName()).isEqualTo("New Name");
        assertThat(user.getBio()).isEqualTo("bio text");
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        assertThat(user.getBannerUrl()).isEqualTo("https://example.com/banner.jpg");
        assertThat(user.getWebsiteUrl()).isEqualTo("https://example.com");
        assertThat(user.isPrivate()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void updateMyProfile_nullFields_entityUnchanged() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        user.setDisplayName("Original");
        user.setBio("original bio");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        service.updateMyProfile(
                id, new UpdateProfileRequest(null, null, null, null, null, null, null));

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getDisplayName()).isEqualTo("Original");
        assertThat(user.getBio()).isEqualTo("original bio");
        verify(userRepository, never()).existsByUsername(any());
    }

    @Test
    void updateMyProfile_takenUsername_throws409() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.updateMyProfile(
                                        id,
                                        new UpdateProfileRequest(
                                                "taken", null, null, null, null, null, null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.USER_USERNAME_ALREADY_EXISTS);

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateMyProfile_sameUsername_skipsUniquenessCheck() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        service.updateMyProfile(
                id, new UpdateProfileRequest("alice", null, null, null, null, null, null));

        verify(userRepository, never()).existsByUsername(any());
        verify(userRepository).save(user);
    }

    @Test
    void updateMyProfile_emptyStringBio_clearsFieldToNull() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        user.setBio("existing bio");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        service.updateMyProfile(
                id, new UpdateProfileRequest(null, null, "", null, null, null, null));

        assertThat(user.getBio()).isNull();
    }

    @Test
    void updateMyProfile_bannerUrlProvided_appliedToEntityAndSaved() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        service.updateMyProfile(
                id,
                new UpdateProfileRequest(
                        null, null, null, null, "https://example.com/banner.jpg", null, null));

        assertThat(user.getBannerUrl()).isEqualTo("https://example.com/banner.jpg");
        verify(userRepository).save(user);
    }

    @Test
    void updateMyProfile_emptyStringBannerUrl_clearsFieldToNull() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        user.setBannerUrl("https://example.com/existing-banner.jpg");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        service.updateMyProfile(
                id, new UpdateProfileRequest(null, null, null, null, "", null, null));

        assertThat(user.getBannerUrl()).isNull();
    }

    @Test
    void updateMyProfile_nullBannerUrl_entityUnchanged() {
        UUID id = UUID.randomUUID();
        User user = activeUser(id, "alice");
        user.setBannerUrl("https://example.com/existing-banner.jpg");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toProfileResponse(user)).thenReturn(profileResponse(id));

        service.updateMyProfile(
                id, new UpdateProfileRequest(null, null, null, null, null, null, null));

        assertThat(user.getBannerUrl()).isEqualTo("https://example.com/existing-banner.jpg");
    }

    // ── getUserProfile ────────────────────────────────────────────────────────

    @Test
    void getUserProfile_publicAccount_authenticated_returnsWithCounts() {
        UUID id = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User user = publicUser(id, "bob");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(socialService.isBlockedBetween(viewerId, id)).thenReturn(false);
        when(userMapper.toPublicProfileResponse(user, 0, 0, 0, ViewerRelationshipResponse.NONE))
                .thenReturn(publicProfileResponse(id, 0, 0, 0));

        service.getUserProfile(viewerId, id);

        verify(userMapper).toPublicProfileResponse(user, 0, 0, 0, ViewerRelationshipResponse.NONE);
    }

    @Test
    void getUserProfile_publicAccount_unauthenticated_countsPassedAsNull() {
        UUID id = UUID.randomUUID();
        User user = publicUser(id, "bob");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toPublicProfileResponse(
                        user, null, null, null, ViewerRelationshipResponse.NONE))
                .thenReturn(publicProfileResponse(id, null, null, null));

        PublicUserProfileResponse result = service.getUserProfile(null, id);

        verify(userMapper)
                .toPublicProfileResponse(user, null, null, null, ViewerRelationshipResponse.NONE);
        assertThat(result.followerCount()).isNull();
        assertThat(result.followingCount()).isNull();
        assertThat(result.postCount()).isNull();
    }

    @Test
    void getUserProfile_privateAccount_nonFollower_returnsMaskedCounts() {
        UUID id = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User user = privateUser(id, "carol");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(socialService.isBlockedBetween(viewerId, id)).thenReturn(false);
        when(socialService.hasAcceptedFollow(viewerId, id)).thenReturn(false);
        when(userMapper.toPublicProfileResponse(
                        user, null, null, null, ViewerRelationshipResponse.NONE))
                .thenReturn(publicProfileResponse(id, null, null, null));

        PublicUserProfileResponse result = service.getUserProfile(viewerId, id);

        verify(userMapper)
                .toPublicProfileResponse(user, null, null, null, ViewerRelationshipResponse.NONE);
        assertThat(result.followerCount()).isNull();
        assertThat(result.followingCount()).isNull();
        assertThat(result.postCount()).isNull();
    }

    @Test
    void getUserProfile_privateAccount_acceptedFollower_returnsFullProfile() {
        UUID id = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User user = privateUser(id, "carol");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(socialService.isBlockedBetween(viewerId, id)).thenReturn(false);
        when(socialService.hasAcceptedFollow(viewerId, id)).thenReturn(true);
        when(userMapper.toPublicProfileResponse(user, 0, 0, 0, ViewerRelationshipResponse.NONE))
                .thenReturn(publicProfileResponse(id, 0, 0, 0));

        service.getUserProfile(viewerId, id);

        verify(userMapper).toPublicProfileResponse(user, 0, 0, 0, ViewerRelationshipResponse.NONE);
    }

    @Test
    void getUserProfile_privateAccount_unauthenticated_returnsMaskedCounts() {
        UUID id = UUID.randomUUID();
        User user = privateUser(id, "carol");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toPublicProfileResponse(
                        user, null, null, null, ViewerRelationshipResponse.NONE))
                .thenReturn(publicProfileResponse(id, null, null, null));

        PublicUserProfileResponse result = service.getUserProfile(null, id);

        verify(userMapper)
                .toPublicProfileResponse(user, null, null, null, ViewerRelationshipResponse.NONE);
        assertThat(result.followerCount()).isNull();
    }

    @Test
    void getUserProfile_blockedCaller_throwsNotFound() {
        UUID id = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User user = publicUser(id, "bob");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(socialService.isBlockedBetween(viewerId, id)).thenReturn(true);

        assertThatThrownBy(() -> service.getUserProfile(viewerId, id))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void getUserProfile_owner_returnsFullProfile() {
        UUID id = UUID.randomUUID();
        User user = privateUser(id, "carol");
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
        when(userMapper.toPublicProfileResponse(user, 0, 0, 0, ViewerRelationshipResponse.NONE))
                .thenReturn(publicProfileResponse(id, 0, 0, 0));

        service.getUserProfile(id, id);

        verify(userMapper).toPublicProfileResponse(user, 0, 0, 0, ViewerRelationshipResponse.NONE);
    }

    @Test
    void getUserProfile_unknownUser_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserProfile(null, id))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    // ── getMySettings ─────────────────────────────────────────────────────────

    @Test
    void getMySettings_settingsExist_returnsSettingsResponse() {
        UUID id = UUID.randomUUID();
        UserSettings settings = defaultSettings(id);
        UserSettingsResponse expected = settingsResponse();
        when(settingsRepository.findById(id)).thenReturn(Optional.of(settings));
        when(userMapper.toSettingsResponse(settings)).thenReturn(expected);

        UserSettingsResponse result = service.getMySettings(id);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getMySettings_settingsNotFound_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(settingsRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMySettings(id))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    // ── updateMySettings ──────────────────────────────────────────────────────

    @Test
    void updateMySettings_nonNullFields_appliedToEntity() {
        UUID id = UUID.randomUUID();
        UserSettings settings = defaultSettings(id);
        when(settingsRepository.findById(id)).thenReturn(Optional.of(settings));
        when(userMapper.toSettingsResponse(settings)).thenReturn(settingsResponse());

        service.updateMySettings(
                id,
                new UpdateSettingsRequest(
                        false, false, false, false, false, false, false, false, false));

        assertThat(settings.isNotifyLikes()).isFalse();
        assertThat(settings.isNotifyComments()).isFalse();
        assertThat(settings.isNotifyFollows()).isFalse();
        assertThat(settings.isNotifyMentions()).isFalse();
        assertThat(settings.isNotifyMessages()).isFalse();
        assertThat(settings.isShowActivityStatus()).isFalse();
        assertThat(settings.isAllowStoryReplies()).isFalse();
        assertThat(settings.isAllowMessageRequests()).isFalse();
        verify(settingsRepository).save(settings);
    }

    @Test
    void updateMySettings_nullFields_preservedOnEntity() {
        UUID id = UUID.randomUUID();
        UserSettings settings = defaultSettings(id);
        when(settingsRepository.findById(id)).thenReturn(Optional.of(settings));
        when(userMapper.toSettingsResponse(settings)).thenReturn(settingsResponse());

        service.updateMySettings(
                id,
                new UpdateSettingsRequest(null, null, null, null, null, null, null, null, null));

        assertThat(settings.isNotifyLikes()).isTrue();
        assertThat(settings.isNotifyComments()).isTrue();
        verify(settingsRepository).save(settings);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static User activeUser(UUID id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@example.com")
                .isPrivate(false)
                .isVerified(false)
                .build();
    }

    private static User publicUser(UUID id, String username) {
        return activeUser(id, username);
    }

    private static User privateUser(UUID id, String username) {
        User u = activeUser(id, username);
        u.setPrivate(true);
        return u;
    }

    private static UserSettings defaultSettings(UUID userId) {
        return UserSettings.builder()
                .userId(userId)
                .notifyLikes(true)
                .notifyComments(true)
                .notifyFollows(true)
                .notifyMentions(true)
                .notifyMessages(true)
                .showActivityStatus(true)
                .allowStoryReplies(true)
                .allowMessageRequests(true)
                .build();
    }

    private static UserProfileResponse profileResponse(UUID id) {
        return new UserProfileResponse(
                id,
                "alice",
                "alice@example.com",
                "Alice",
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                0,
                0,
                0,
                null);
    }

    private static PublicUserProfileResponse publicProfileResponse(
            UUID id, Integer follower, Integer following, Integer post) {
        return new PublicUserProfileResponse(
                id,
                "bob",
                "Bob",
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                follower,
                following,
                post,
                null,
                ViewerRelationshipResponse.NONE);
    }

    private static UserSettingsResponse settingsResponse() {
        return new UserSettingsResponse(true, true, true, true, true, true, true, true, true, null);
    }
}
