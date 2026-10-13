package com.app.modules.users.service.impl;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserPrivacySettingsService;

@Service
public class UserPrivacySettingsServiceImpl implements UserPrivacySettingsService {

    private static final Logger log = LoggerFactory.getLogger(UserPrivacySettingsServiceImpl.class);

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

    // A missing row is a broken invariant on the target's side, and these readers sit on another
    // account's comment or message path, so throwing would fail an actor who did nothing wrong. The
    // defaults come from a freshly built entity, which carries the same values as the column
    // defaults, so the two cannot drift apart.
    private UserSettings settingsOf(UUID userId) {
        return settingsRepository
                .findById(userId)
                .orElseGet(
                        () -> {
                            log.error(
                                    "Account {} has no user_settings row; using the column defaults",
                                    userId);
                            return UserSettings.builder().userId(userId).build();
                        });
    }
}
