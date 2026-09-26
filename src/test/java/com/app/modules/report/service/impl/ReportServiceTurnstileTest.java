package com.app.modules.report.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.turnstile.AuthTurnstileGuard;
import com.app.common.turnstile.TurnstileOutcome;
import com.app.common.turnstile.TurnstileProperties;
import com.app.common.turnstile.TurnstileVerifier;
import com.app.modules.report.dto.request.CreateReportRequest;
import com.app.modules.report.enums.ReportReason;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.mapper.ReportMapper;
import com.app.modules.report.repository.ReportRepository;

/**
 * Report submission shares the fail-open policy of the auth surfaces, so it gets the same three
 * cases: a rejection writes nothing, an outage lets the report through, and the kill switch takes
 * the check out entirely.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTurnstileTest {

    private static final String TOKEN = "turnstile-token";
    private static final String CLIENT_IP = "4.5.6.7";

    @Mock private ReportRepository reportRepository;
    @Mock private ReportMapper reportMapper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TurnstileVerifier verifier;

    private TurnstileProperties turnstileProperties;
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        turnstileProperties = new TurnstileProperties();
        lenient()
                .when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(
                        inv -> {
                            TransactionCallback<?> cb = inv.getArgument(0);
                            return cb.doInTransaction(null);
                        });
        service =
                new ReportServiceImpl(
                        reportRepository,
                        reportMapper,
                        new AuthTurnstileGuard(verifier, turnstileProperties),
                        transactionTemplate);
    }

    private static CreateReportRequest request(String token) {
        return new CreateReportRequest(
                ReportType.POST, ReportReason.SPAM, UUID.randomUUID(), "Spam", token);
    }

    @Test
    void submitReport_rejected_refusesAndWritesNothing() {
        when(verifier.verify(any(), any(), any())).thenReturn(TurnstileOutcome.REJECTED);

        assertThatThrownBy(() -> service.submitReport(UUID.randomUUID(), request("bad"), CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);

        verify(reportRepository, never()).findOwnerId(any(), any());
        verify(reportRepository, never()).saveAndFlush(any());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void submitReport_unavailable_proceedsToTheTargetChecks() {
        when(verifier.verify(any(), any(), any())).thenReturn(TurnstileOutcome.UNAVAILABLE);
        when(reportRepository.findOwnerId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitReport(UUID.randomUUID(), request(TOKEN), CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_TARGET_NOT_FOUND);
    }

    @Test
    void submitReport_killSwitchDisabled_acceptsABlankTokenWithoutVerifying() {
        turnstileProperties.getAuth().setEnabled(false);
        when(reportRepository.findOwnerId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitReport(UUID.randomUUID(), request(null), CLIENT_IP))
                .isInstanceOf(AppException.class);

        verify(verifier, never()).verify(any(), any(), any());
    }
}
