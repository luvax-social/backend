package com.app.common.config.security;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds security-related configuration from {@code app.security.*}.
 *
 * <p>{@code trustedProxyCidrs}: IP addresses or CIDR ranges of proxies whose {@code
 * X-Forwarded-For} header values are trusted for real-IP extraction. Defaults to an empty list (no
 * proxy trusted). {@code maxLoginBodyBytes}: upper bound on the cached request body size for the
 * login endpoint rate-limiter. {@code cookieSigningSecret}: secret used to compute the HMAC-SHA256
 * signature appended to the OAuth2 authorization-request cookie; must be at least 32 characters.
 *
 * <p>{@code @Validated} is required for any of the constraints above to execute. Spring Boot binds
 * a {@code @ConfigurationProperties} type without validating it unless the type carries that
 * annotation, so removing it silently disables the 32-character floor on the signing secret rather
 * than causing a visible failure.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotNull @DefaultValue List<String> trustedProxyCidrs,
        // Raised from 2048 when the auth surfaces took a turnstileToken field. The cap bounds how
        // much AuthRateLimitFilter buffers to extract the rate-limit identifier; a Turnstile token
        // runs to about 2048 characters, which does not fit alongside the existing login payload
        // inside 2048 bytes. 4096 remains a bound well under Tomcat's 1 MB limit.
        @PositiveOrZero @DefaultValue("4096") int maxLoginBodyBytes,
        @NotBlank @Size(min = 32) String cookieSigningSecret) {}
