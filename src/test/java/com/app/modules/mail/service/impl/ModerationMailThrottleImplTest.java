package com.app.modules.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.security.service.RateLimiterService;

@ExtendWith(MockitoExtension.class)
class ModerationMailThrottleImplTest {

    @Mock private RateLimiterService rateLimiterService;

    private ModerationMailThrottleImpl throttle;

    @BeforeEach
    void setUp() {
        throttle = new ModerationMailThrottleImpl(rateLimiterService);
    }

    @Test
    void tryAcquire_withinBudget_isAllowed() {
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        assertThat(throttle.tryAcquire("target@example.com")).isTrue();
    }

    @Test
    void tryAcquire_budgetExhausted_isRefused() {
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(false);

        assertThat(throttle.tryAcquire("target@example.com")).isFalse();
    }

    @Test
    void tryAcquire_appliesFivePerHour() {
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        throttle.tryAcquire("target@example.com");

        verify(rateLimiterService).isAllowed(anyString(), eq(5), eq(3600L));
    }

    // The address is hashed rather than embedded, so an operator reading Redis keys does not read a
    // list of everyone the platform has disciplined.
    @Test
    void tryAcquire_neverPutsTheAddressInTheKey() {
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        throttle.tryAcquire("target@example.com");

        assertThat(capturedKey())
                .startsWith("mail:moderation:")
                .doesNotContain("target@example.com");
    }

    @Test
    void tryAcquire_normalisesCaseAndSurroundingSpace() {
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        throttle.tryAcquire("Target@Example.com");
        String mixedCaseKey = capturedKey();

        throttle.tryAcquire("  target@example.com  ");

        assertThat(capturedKeys()).last().isEqualTo(mixedCaseKey);
    }

    @Test
    void tryAcquire_differentRecipients_getSeparateBudgets() {
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        throttle.tryAcquire("one@example.com");
        throttle.tryAcquire("two@example.com");

        assertThat(capturedKeys()).doesNotHaveDuplicates();
    }

    private String capturedKey() {
        return capturedKeys().get(capturedKeys().size() - 1);
    }

    private java.util.List<String> capturedKeys() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterService, org.mockito.Mockito.atLeastOnce())
                .isAllowed(captor.capture(), anyInt(), anyLong());
        return captor.getAllValues();
    }
}
