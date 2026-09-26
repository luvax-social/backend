package com.app.modules.mail.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.app.common.security.service.RateLimiterService;
import com.app.modules.mail.service.ModerationMailThrottle;

/**
 * Per-recipient moderation mail budget, backed by the existing Redis sliding window.
 *
 * <p>Delegates to {@link RateLimiterService} rather than restating its Lua script. That script is
 * already the audited sliding-window implementation in this codebase, and a second copy of it would
 * be a second thing to keep correct. The consequence is that the key carries that service's {@code
 * auth:ratelimit:} prefix, which is a cosmetic cost paid for having one implementation.
 *
 * <p>The recipient address is hashed into the key rather than embedded, so an operator reading
 * Redis keys does not read a list of everyone the platform has disciplined.
 *
 * <p>Failing closed is inherited from the rate limiter and is the right direction here: a Redis
 * outage suppresses a notice rather than risking an unbounded send loop against the provider, and
 * the account still learns the outcome the next time it tries to sign in.
 */
@Service
public class ModerationMailThrottleImpl implements ModerationMailThrottle {

    private static final String KEY_PREFIX = "mail:moderation:";

    // Five notices an hour per recipient. A moderator working through one account's backlog can
    // legitimately produce several actions in a few minutes, and each is a separate notice, so a
    // limit of one or two would suppress ordinary moderation. Above five in an hour the recipient
    // is being mailed faster than anyone can act on, which is a retry storm or a loop rather than
    // moderation, and that is exactly what this bound exists to stop.
    private static final int MAX_SENDS = 5;
    private static final long WINDOW_SECONDS = 3600L;

    private final RateLimiterService rateLimiterService;

    public ModerationMailThrottleImpl(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean tryAcquire(String recipientEmail) {
        return rateLimiterService.isAllowed(
                KEY_PREFIX + hash(recipientEmail), MAX_SENDS, WINDOW_SECONDS);
    }

    private static String hash(String recipientEmail) {
        String normalized = recipientEmail.trim().toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform, so this cannot happen on a supported JVM.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
