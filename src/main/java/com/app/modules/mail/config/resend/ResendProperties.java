package com.app.modules.mail.config.resend;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds Resend transport configuration from {@code app.mail.resend.*}.
 *
 * <p>Holds the provider credential only. Sender identity and application metadata are
 * transport-neutral and live in {@code app.mail.*}; never inline secrets here.
 */
@ConfigurationProperties(prefix = "app.mail.resend")
@Getter
@Setter
public class ResendProperties {

    private String apiKey;

    /**
     * Total bound on one provider call, enforced by the caller rather than by the SDK.
     *
     * <p>The Resend SDK 3.1.0 exposes no HTTP configuration: {@code Resend} has a single {@code
     * Resend(String)} constructor, {@code BaseService} builds its own client, and that client is a
     * bare {@code new OkHttpClient()}. OkHttp's own defaults therefore apply and cannot be changed
     * - ten seconds each for connect, read and write - and there is no call timeout at all, so a
     * provider that accepts a connection and then stalls between reads can hold a consumer thread
     * far longer than any single default suggests. This property is the one bound that can be
     * imposed from outside the SDK, and it is what the mail sender enforces.
     *
     * <p>Twenty seconds is deliberately above the sum a healthy call could take and well below the
     * point where a stalled provider would starve the consumer.
     */
    private Duration callTimeout = Duration.ofSeconds(20);
}
