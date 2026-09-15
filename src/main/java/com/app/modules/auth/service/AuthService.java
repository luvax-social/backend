package com.app.modules.auth.service;

import jakarta.servlet.http.HttpServletRequest;

import com.app.modules.auth.dto.request.ForgotPasswordRequest;
import com.app.modules.auth.dto.request.LoginRequest;
import com.app.modules.auth.dto.request.OAuth2ExchangeRequest;
import com.app.modules.auth.dto.request.RegisterRequest;
import com.app.modules.auth.dto.request.ResendVerificationRequest;
import com.app.modules.auth.dto.request.ResetPasswordRequest;
import com.app.modules.auth.dto.response.AuthResponse;

/**
 * Email + password authentication flows. Tokens issued by these methods are stateless JWT access
 * tokens paired with opaque refresh tokens.
 */
public interface AuthService {

    /**
     * Registers a new user and records mail side-effect events for verification and welcome
     * messages. No session is issued - the user must verify their email before logging in.
     *
     * <p>The submitted Turnstile token is verified before the uniqueness check and before any
     * write, so a rejected challenge creates no account and enqueues no mail.
     *
     * @param request validated registration payload
     * @param httpRequest source request, used to record the origin the account was created from
     * @throws com.app.common.exception.AppException with {@link
     *     com.app.common.enums.ApiErrorCode#AUTH_CAPTCHA_FAILED} when the challenge is rejected
     */
    void register(RegisterRequest request, HttpServletRequest httpRequest);

    /**
     * Authenticates an existing user by email or username and password and issues a fresh session
     * pair.
     *
     * <p>The submitted Turnstile token is verified before the credential lookup, so a rejected
     * challenge costs no password comparison and reveals nothing about the account.
     *
     * @param request validated login payload
     * @param httpRequest underlying servlet request, used to capture device metadata
     * @return access + refresh tokens with the user summary
     * @throws com.app.common.exception.AppException with {@link
     *     com.app.common.enums.ApiErrorCode#AUTH_CAPTCHA_FAILED} when the challenge is rejected
     */
    AuthResponse login(LoginRequest request, HttpServletRequest httpRequest);

    /**
     * Rotates the supplied refresh token, mints a new access token, and returns both.
     *
     * @param rawRefreshToken raw refresh token already resolved from the request body or cookie; an
     *     empty value is rejected as an invalid token rather than treated specially
     * @param httpRequest underlying servlet request, used to capture the new IP
     * @return new access + refresh tokens with the user summary
     */
    AuthResponse refresh(String rawRefreshToken, HttpServletRequest httpRequest);

    /**
     * Revokes the supplied refresh token. The operation is idempotent, including when no token is
     * supplied at all.
     *
     * @param rawRefreshToken raw refresh token already resolved from the request body or cookie
     */
    void logout(String rawRefreshToken);

    /**
     * Consumes the email-verification token, marks the credential as verified, and issues the first
     * session pair so the user is logged in immediately.
     *
     * @param rawToken raw verification token from the link the user followed
     * @param httpRequest underlying servlet request, used to capture device metadata
     * @return access + refresh tokens with the verified user summary
     */
    AuthResponse verifyEmail(String rawToken, HttpServletRequest httpRequest);

    /**
     * Records a fresh verification-mail request if an account with the supplied email exists.
     * Behaviour is silent when the address is unknown to avoid account enumeration.
     *
     * <p>Turnstile verification happens inside the same equalized response window as the lookup, so
     * the challenge does not reintroduce a timing channel the equalizer exists to close.
     *
     * @param request payload containing the email address and the Turnstile token
     * @param clientIp the caller's address as resolved by {@code IpExtractor}, or null
     * @throws com.app.common.exception.AppException with {@link
     *     com.app.common.enums.ApiErrorCode#AUTH_CAPTCHA_FAILED} when the challenge is rejected
     */
    void resendVerification(ResendVerificationRequest request, String clientIp);

    /**
     * Records a password-reset mail request if an account with the supplied email exists. Behaviour
     * is silent when the address is unknown.
     *
     * <p>Turnstile verification happens inside the same equalized response window as the lookup, so
     * the challenge does not reintroduce a timing channel the equalizer exists to close.
     *
     * @param request payload containing the email address and the Turnstile token
     * @param clientIp the caller's address as resolved by {@code IpExtractor}, or null
     * @throws com.app.common.exception.AppException with {@link
     *     com.app.common.enums.ApiErrorCode#AUTH_CAPTCHA_FAILED} when the challenge is rejected
     */
    void forgotPassword(ForgotPasswordRequest request, String clientIp);

    /**
     * Consumes a password-reset token, replaces the password hash, revokes every active session for
     * that user, and records a security-notification mail event.
     *
     * <p>The submitted Turnstile token is verified before the reset token is consumed, so a
     * rejected challenge leaves the single-use reset link still usable.
     *
     * @param request payload containing the raw token, the new password and the Turnstile token
     * @param clientIp the caller's address as resolved by {@code IpExtractor}, or null
     * @throws com.app.common.exception.AppException with {@link
     *     com.app.common.enums.ApiErrorCode#AUTH_CAPTCHA_FAILED} when the challenge is rejected
     */
    void resetPassword(ResetPasswordRequest request, String clientIp);

    /**
     * Consumes a short-lived OAuth2 exchange code and issues an access/refresh token pair for the
     * resolved user.
     *
     * <p>The exchange code is deleted atomically on first use so it cannot be redeemed twice. The
     * returned token pair is identical in structure to the one returned by the login endpoint.
     *
     * @param request payload containing the raw exchange code
     * @param httpRequest underlying servlet request, used to capture device metadata
     * @return access + refresh tokens with the authenticated user summary
     * @throws com.app.common.exception.AppException with {@link
     *     com.app.common.enums.ApiErrorCode#AUTH_OAUTH2_EXCHANGE_CODE_INVALID} when the code is
     *     absent or expired
     */
    AuthResponse exchangeOAuth2Code(OAuth2ExchangeRequest request, HttpServletRequest httpRequest);
}
