package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.repository.UserSettingsRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
    void readers_missingSettingsRow_answerTheColumnDefaultsAndLogAtError() {
        when(settingsRepository.findById(userId)).thenReturn(Optional.empty());
        Logger logger = (Logger) LoggerFactory.getLogger(UserPrivacySettingsServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(service.hiddenWordsSystemEnabled(userId)).isTrue();
            assertThat(service.messagePermissionOf(userId)).isEqualTo(MessagePermission.EVERYONE);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.ERROR, Level.ERROR);
    }
}
