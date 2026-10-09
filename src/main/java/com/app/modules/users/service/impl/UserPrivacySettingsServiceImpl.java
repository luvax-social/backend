package com.app.modules.users.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserPrivacySettingsService;

@Service
public class UserPrivacySettingsServiceImpl implements UserPrivacySettingsService {

    private final UserSettingsRepository settingsRepository;

    public UserPrivacySettingsServiceImpl(UserSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hiddenWordsSystemEnabled(UUID userId) {
        return settingsOf(userId).isHiddenWordsSystemEnabled();
    }

    @Override
    @Transactional(readOnly = true)
    public MessagePermission messagePermissionOf(UUID userId) {
        return settingsOf(userId).getMessagePermission();
    }

    private UserSettings settingsOf(UUID userId) {
        return settingsRepository
                .findById(userId)
                .orElseThrow(() -> new AppException(ApiErrorCode.NOT_FOUND));
    }
}
