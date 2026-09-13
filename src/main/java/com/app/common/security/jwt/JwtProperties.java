package com.app.common.security.jwt;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds JWT configuration from {@code app.jwt.*}.
 *
 * <p>{@code @Validated} is required for the constraints below to execute; Spring Boot binds a
 * {@code @ConfigurationProperties} type without validating it unless the type carries that
 * annotation, so removing it disables them silently rather than visibly. The constraints reject a
 * variable that is present but empty. They do not reject one that is absent entirely, because an
 * unresolved placeholder binds as its own literal text and {@code "${JWT_ISSUER}"} is not blank -
 * {@code RequiredEnvironmentGuard} is what refuses to start in that case.
 *
 * @param secret HMAC-SHA256 signing key (must be at least 32 characters)
 * @param issuer value placed in the {@code iss} claim
 * @param audience value placed in the {@code aud} claim
 * @param accessTokenTtl access token lifetime in seconds
 * @param refreshTokenTtl refresh token lifetime in seconds
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        @NotBlank String audience,
        long accessTokenTtl,
        long refreshTokenTtl) {}
