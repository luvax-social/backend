package com.app.modules.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationRepository;

class NotificationTypePolicyTest {

    @Test
    void isEnabled_typeSwitchedOffByTheOperator_isFalse() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.findDisabledTypeKeys()).thenReturn(List.of("like_post"));
        NotificationTypePolicy policy = new NotificationTypePolicy(repository, () -> 0L);

        assertThat(policy.isEnabled(NotificationType.LIKE_POST)).isFalse();
        assertThat(policy.isEnabled(NotificationType.COMMENT_POST)).isTrue();
    }

    @Test
    void isEnabled_unknownConfigKey_isIgnored() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.findDisabledTypeKeys()).thenReturn(List.of("retired_type"));
        NotificationTypePolicy policy = new NotificationTypePolicy(repository, () -> 0L);

        assertThat(policy.isEnabled(NotificationType.WARNING)).isTrue();
    }

    @Test
    void isEnabled_readsTheTableOncePerRefreshInterval() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.findDisabledTypeKeys()).thenReturn(List.of());
        AtomicLong now = new AtomicLong(0);
        NotificationTypePolicy policy = new NotificationTypePolicy(repository, now::get);

        policy.isEnabled(NotificationType.FOLLOW);
        policy.isEnabled(NotificationType.FOLLOW);
        now.addAndGet(NotificationTypePolicy.REFRESH_INTERVAL.toNanos());
        policy.isEnabled(NotificationType.FOLLOW);

        verify(repository, times(2)).findDisabledTypeKeys();
    }
}
