package com.app.modules.support.service.impl;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.turnstile.TurnstileOutcome;
import com.app.common.turnstile.TurnstileSurface;
import com.app.common.turnstile.TurnstileVerifier;
import com.app.modules.admin.entity.AdminAction;
import com.app.modules.admin.messaging.AppealCategories;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.auth.service.impl.ForgotPasswordTimingEqualizer;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.support.dto.request.ResendAppealLinkRequest;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.service.AppealLinkMailer;
import com.app.modules.support.service.AppealRecoveryService;
import com.app.modules.support.service.SupportTokenService;
import com.app.modules.users.entity.User;
import com.app.modules.users.repository.UserRepository;

/**
 * Re-mints and mails the appeal link for an account whose moderation notice never arrived.
 *
 * <p>Deliberately not transactional. The equalizer below sleeps for the remainder of the response
 * floor on every call, and a transaction spanning that sleep would hold a pooled connection idle
 * for it. {@code AuthServiceImpl.forgotPassword} avoids the same trap the same way: the reads here
 * each take their own short transaction and none of them needs to be atomic with the others,
 * because this path writes no row.
 */
@Service
public class AppealRecoveryServiceImpl implements AppealRecoveryService {

    private static final String APPEAL_PATH = "/support/appeal";

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final AdminActionRepository adminActionRepository;
    private final SupportTokenService supportTokenService;
    private final AppealLinkMailer appealLinkMailer;
    private final TurnstileVerifier turnstileVerifier;
    private final ForgotPasswordTimingEqualizer timingEqualizer;
    private final MailProperties mailProperties;

    public AppealRecoveryServiceImpl(
            UserRepository userRepository,
            UserCredentialRepository userCredentialRepository,
            AdminActionRepository adminActionRepository,
            SupportTokenService supportTokenService,
            AppealLinkMailer appealLinkMailer,
            TurnstileVerifier turnstileVerifier,
            ForgotPasswordTimingEqualizer timingEqualizer,
            MailProperties mailProperties) {
        this.userRepository = userRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.adminActionRepository = adminActionRepository;
        this.supportTokenService = supportTokenService;
        this.appealLinkMailer = appealLinkMailer;
        this.turnstileVerifier = turnstileVerifier;
        this.timingEqualizer = timingEqualizer;
        this.mailProperties = mailProperties;
    }

    @Override
    public void resendAppealLink(ResendAppealLinkRequest request, String clientIp) {
        long startNanos = System.nanoTime();
        try {
            // Inside the equalized window, not before it. The siteverify call takes real and
            // variable time; measuring from before it would leave that variance outside the floor
            // and hand back the enumeration channel the equalizer exists to close. A rejected
            // challenge throws from here and still leaves through the finally below, so a refusal
            // takes the same wall time as a success.
            //
            // Fails closed on every outcome that is not a positive confirmation, matching the
            // public support form rather than the auth surfaces. Those can afford to admit a
            // request Cloudflare did not answer for because a per-caller rule stands behind them.
            // This endpoint has no per-caller identity and it sends mail, so the challenge is its
            // only real control and an outage must stop it rather than open it.
            if (turnstileVerifier.verify(
                            request.turnstileToken(), clientIp, TurnstileSurface.RESEND_APPEAL_LINK)
                    != TurnstileOutcome.VERIFIED) {
                throw new AppException(ApiErrorCode.SUPPORT_CAPTCHA_FAILED);
            }
            resolveAndSend(request.contactEmail().trim().toLowerCase(Locale.ROOT));
        } finally {
            timingEqualizer.equalizeFrom(startNanos);
        }
    }

    /**
     * Resolves the address to a decision and mails its link, or silently does nothing.
     *
     * <p>Every negative case returns the same way it returns on success: no account, an account
     * with no unverified-address clearance, no un-appealed decision, or a mail the transport
     * refused. The caller has no way to tell them apart, which is the point.
     */
    private void resolveAndSend(String email) {
        Optional<User> account = userRepository.findByEmailIgnoreCase(email);
        if (account.isEmpty()) {
            return;
        }
        UUID userId = account.get().getId();
        // The same refusal ModerationMailEventHandler applies, and for the same reason. The
        // platform has never proved an unverified address belongs to the account, so mailing it
        // would tell whoever actually owns that mailbox both that the address is registered here
        // and that a moderation decision was taken about its supposed owner. That is worse here
        // than there, because an anonymous caller chooses the address this path is asked about.
        if (!emailVerified(userId)) {
            return;
        }
        Optional<AdminAction> decision =
                adminActionRepository.findMostRecentAppealable(
                        userId, AppealCategories.appealableActionTypeLabels());
        if (decision.isEmpty()) {
            return;
        }
        AdminAction action = decision.get();
        SupportCategory category = AppealCategories.forAction(action.getActionType());
        if (category == null) {
            // Unreachable while the query filters on the same set this reads, and left as a guard
            // rather than an assertion because the two would have to drift silently for it to fire.
            return;
        }
        // The ordinary issue path, so the reverse-key contract destroys any previous link for this
        // decision. One decision never holds two live links, however often a replacement is asked
        // for. The address mailed is the account's own, never the one the caller typed.
        String token = supportTokenService.createAppealToken(userId, action.getId(), category);
        appealLinkMailer.sendAppealLink(account.get().getEmail(), appealUrl(token));
    }

    private boolean emailVerified(UUID userId) {
        return userCredentialRepository
                .findByUserId(userId)
                .map(credential -> credential.isEmailVerified())
                // An account with no credential row signed in through OAuth2 only. The provider
                // verified the address before it ever reached this system.
                .orElse(true);
    }

    private String appealUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(mailProperties.getFrontendBaseUrl())
                .path(APPEAL_PATH)
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }
}
