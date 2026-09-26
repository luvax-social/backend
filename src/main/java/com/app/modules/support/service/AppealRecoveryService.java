package com.app.modules.support.service;

import com.app.common.exception.AppException;
import com.app.modules.support.dto.request.ResendAppealLinkRequest;

/** Re-sends the appeal link for an account whose moderation notice never arrived. */
public interface AppealRecoveryService {

    /**
     * Re-mints and mails the appeal link for the most recent un-appealed decision on an account.
     *
     * <p>The gap this closes: the link contesting a specific decision exists only inside one email,
     * minted when that notice was sent. A bounced, filtered or deleted notice therefore removed the
     * account's only route to contest the decision permanently, and a banned or suspended account
     * cannot reach any authenticated surface to recover it.
     *
     * <p>Answers identically whether or not the address matches anything, in body, in status code
     * and in elapsed time. The Turnstile call runs inside the equalized window rather than before
     * it, because the remote verification takes real and variable time and measuring from before it
     * would leave that variance outside the floor.
     *
     * <p>Turnstile is fail-closed here, unlike the auth surfaces. Those can afford to admit a
     * request Cloudflare did not answer for because a per-caller rule stands behind them; this
     * endpoint has no per-caller identity at all and triggers mail, so the challenge is its only
     * real control and an outage must stop it rather than open it.
     *
     * <p>Re-minting goes through the ordinary issue path, so the reverse-key contract destroys any
     * previous link for the same decision. One decision never has two live links.
     *
     * @param request the address and the challenge response
     * @param clientIp the caller address, for the Turnstile verification
     * @throws AppException {@code SUPPORT_CAPTCHA_FAILED} when the challenge is not accepted
     */
    void resendAppealLink(ResendAppealLinkRequest request, String clientIp);
}
