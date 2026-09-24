package com.app.modules.users.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.users.dto.response.NotificationPreferencesResponse;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserNotificationPreferencesService;

@Service
public class UserNotificationPreferencesServiceImpl implements UserNotificationPreferencesService {

    private final UserSettingsRepository userSettingsRepository;

    public UserNotificationPreferencesServiceImpl(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse findNotificationPreferences(UUID userId) {
        return userSettingsRepository
                .findById(userId)
                .map(
                        settings ->
                                new NotificationPreferencesResponse(
                                        settings.isNotifyLikes(),
                                        settings.isNotifyComments(),
                                        settings.isNotifyFollows(),
                                        settings.isNotifyMentions(),
                                        settings.isNotifyMessages()))
                .orElse(NotificationPreferencesResponse.ALL_ENABLED);
    }
}
