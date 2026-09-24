package com.app.modules.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification feed settings bound from the {@code app.notification} namespace.
 *
 * <p>{@code aggregationWindow} is how long a group accepts new actors, measured from its first
 * actor rather than its last, so a steady trickle cannot keep one group alive forever and "12
 * others" stays a meaningful number. It defaults to a day, which is also a story's lifetime.
 *
 * <p>{@code seenSessionGap} is the pause that separates two visits. The "new" section is defined by
 * the watermark as it stood at the start of the current visit, so it survives a reload; the first
 * advance after a pause at least this long starts a new visit.
 *
 * <p>{@code verifiedResyncBatchSize} bounds each transaction of the background rewrite that follows
 * a verification grant or revocation, so a prolific account never holds one long lock on {@code
 * notifications}.
 */
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        Duration aggregationWindow, Duration seenSessionGap, int verifiedResyncBatchSize) {

    public NotificationProperties {
        aggregationWindow =
                aggregationWindow == null
                                || aggregationWindow.isNegative()
                                || aggregationWindow.isZero()
                        ? Duration.ofHours(24)
                        : aggregationWindow;
        seenSessionGap =
                seenSessionGap == null || seenSessionGap.isNegative() || seenSessionGap.isZero()
                        ? Duration.ofMinutes(30)
                        : seenSessionGap;
        verifiedResyncBatchSize = verifiedResyncBatchSize <= 0 ? 500 : verifiedResyncBatchSize;
    }
}
