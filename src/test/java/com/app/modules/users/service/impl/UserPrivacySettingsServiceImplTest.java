package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.repository.UserSettingsRepository;

@ExtendWith(MockitoExtension.class)
class UserPrivacySettingsServiceImplTest {

    @Mock private UserSettingsRepository settingsRepository;

    private UserPrivacySettingsServiceImpl service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserPrivacySettingsServiceImpl(settingsRepository);
    }

    @Test
    void hiddenWordsSystemEnabled_reflectsTheStoredToggle() {
        when(settingsRepository.findById(userId))
                .thenReturn(
                        Optional.of(
                                UserSettings.builder()
                                        .userId(userId)
                                        .hiddenWordsSystemEnabled(false)
                                        .build()));

        assertThat(service.hiddenWordsSystemEnabled(userId)).isFalse();
    }

    @Test
    void messagePermissionOf_returnsTheStoredPermission() {
        when(settingsRepository.findById(userId))
                .thenReturn(
                        Optional.of(
                                UserSettings.builder()
                                        .userId(userId)
                                        .messagePermission(MessagePermission.NOBODY)
                                        .build()));

        assertThat(service.messagePermissionOf(userId)).isEqualTo(MessagePermission.NOBODY);
    }

    @Test
    void readers_missingSettingsRow_throwNotFound() {
        when(settingsRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.hiddenWordsSystemEnabled(userId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.messagePermissionOf(userId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }
}
