package com.app.modules.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.modules.auth.dto.request.ForgotPasswordRequest;
import com.app.modules.auth.dto.request.LoginRequest;
import com.app.modules.auth.dto.request.OAuth2ExchangeRequest;
import com.app.modules.auth.dto.request.RefreshRequest;
import com.app.modules.auth.dto.request.RegisterRequest;
import com.app.modules.auth.dto.request.ResendVerificationRequest;
import com.app.modules.auth.dto.request.ResetPasswordRequest;
import com.app.modules.auth.dto.response.AuthResponse;
import com.app.modules.auth.dto.response.CurrentSessionResponse;
import com.app.modules.auth.dto.response.WebSocketTicketResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the authentication module.
 *
 * <p>Every anonymous operation below declares {@code security = {@SecurityRequirement(name = "")}}
 * rather than {@code security = {}}. A truly empty array is indistinguishable from the annotation
 * attribute's unset default, so springdoc silently falls back to the global {@code bearerAuth}
 * requirement instead of emitting {@code security: []}. A single requirement with an empty scheme
 * name is springdoc's documented idiom for an explicit override to no security.
 */
@Tag(
        name = "Authentication",
        description = "Registration, login, token management, and password flows")
@RequestMapping(ApiConstants.Auth.ROOT)
public interface AuthApi {

    @Operation(
            summary = "Register a new user",
            description =
                    "Creates a user account and records verification/welcome mail events. No"
                            + " tokens are issued — the client must call /verify-email before logging in.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Account created — verification mail events recorded"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Username or email already in use",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation failure",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.REGISTER)
    ResponseEntity<ApiResponse<Void>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest);

    /** Authenticates an existing user and returns access + refresh tokens. */
    @Operation(
            summary = "Log in",
            description =
                    "Authenticates credentials by email or username and returns an access/refresh"
                            + " token pair. The identifier field accepts either an email address or"
                            + " a username.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Authenticated",
                headers =
                        @Header(
                                name = "Set-Cookie",
                                description =
                                        "HttpOnly refresh cookie carrying the refresh token, scoped"
                                                + " to /api/v1/auth. Named luvax_refresh unless"
                                                + " app.security.refresh-cookie.name overrides it",
                                schema = @Schema(type = "string"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Invalid credentials",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Credentials were correct but the account may not start a session:"
                                + " banned (AUTH_ACCOUNT_LOCKED), suspended or deactivated"
                                + " (AUTH_ACCOUNT_INACTIVE), or email not yet verified"
                                + " (AUTH_EMAIL_NOT_VERIFIED). Raised only after the password is"
                                + " verified, so it never reveals account state to a caller who"
                                + " has not proven knowledge of the credentials.",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation failure",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.LOGIN)
    ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /** Rotates the refresh token and returns a new session pair. */
    @Operation(
            summary = "Refresh tokens",
            description =
                    "Rotates the refresh token and returns a new access/refresh token pair. The"
                            + " token is read from the request body when present and non-blank,"
                            + " otherwise from the HttpOnly refresh cookie. The rotated token"
                            + " is returned both in the response body and as a replacement cookie.",
            security = {@SecurityRequirement(name = "")})
    @Parameter(
            name = "luvax_refresh",
            in = ParameterIn.COOKIE,
            required = false,
            description =
                    "HttpOnly refresh cookie, named luvax_refresh by default and renameable via"
                            + " app.security.refresh-cookie.name. Used only when the request body"
                            + " omits refreshToken or supplies it blank; the body always takes"
                            + " precedence.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Tokens rotated",
                headers =
                        @Header(
                                name = "Set-Cookie",
                                description =
                                        "HttpOnly refresh cookie carrying the rotated refresh"
                                                + " token, scoped to /api/v1/auth. Named"
                                                + " luvax_refresh unless"
                                                + " app.security.refresh-cookie.name overrides it",
                                schema = @Schema(type = "string"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description =
                        "Refresh token invalid or expired, or absent from both the request body"
                                + " and the cookie",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Account is banned (AUTH_ACCOUNT_LOCKED), or suspended or deactivated"
                                + " (AUTH_ACCOUNT_INACTIVE), since a session must not outlive the"
                                + " account state that permitted it",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.REFRESH)
    ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /** Reports which session the calling client is using. */
    @Operation(
            summary = "Identify the calling session",
            description =
                    "Returns the identifier of the session the caller is using, so a reviewer"
                            + " reading its own account's session listing can tell which row it is"
                            + " sitting on. This cannot be derived from the access token: its jti"
                            + " is unique per access token and carries no link to the session, and"
                            + " the refresh cookie is scoped to the auth path so it never reaches"
                            + " the administrative tree. The token is read from the request body"
                            + " when supplied, otherwise from the HttpOnly refresh cookie."
                            + " sessionId is null when the request carried no usable refresh"
                            + " token, which is not an error.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The caller's session identifier, or null"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Authentication required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.SESSION)
    ResponseEntity<ApiResponse<CurrentSessionResponse>> currentSession(
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest);

    /** Revokes the supplied refresh token. Idempotent. */
    @Operation(
            summary = "Log out",
            description =
                    "Revokes the refresh token, blacklists the current access token, and clears the"
                            + " HttpOnly refresh cookie. The token is read from the request"
                            + " body when present, otherwise from the cookie. Idempotent, and still"
                            + " returns 204 when neither source carries a token.")
    @Parameter(
            name = "luvax_refresh",
            in = ParameterIn.COOKIE,
            required = false,
            description =
                    "HttpOnly refresh cookie, named luvax_refresh by default and renameable via"
                            + " app.security.refresh-cookie.name. Used only when the request body"
                            + " omits refreshToken or supplies it blank; the body always takes"
                            + " precedence.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Logged out",
                headers =
                        @Header(
                                name = "Set-Cookie",
                                description =
                                        "Expired refresh cookie (Max-Age=0), named luvax_refresh"
                                                + " unless app.security.refresh-cookie.name"
                                                + " overrides it",
                                schema = @Schema(type = "string"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid access token",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.LOGOUT)
    ResponseEntity<Void> logout(
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /** Verifies an email address using the token embedded in the verification link. */
    @Operation(
            summary = "Verify email address",
            description =
                    "Marks the account's email as verified using the one-time token from the"
                            + " verification link, then issues a session pair so the user is"
                            + " logged in immediately.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Email verified — access + refresh tokens returned",
                headers =
                        @Header(
                                name = "Set-Cookie",
                                description =
                                        "HttpOnly refresh cookie carrying the refresh token, scoped"
                                                + " to /api/v1/auth. Named luvax_refresh unless"
                                                + " app.security.refresh-cookie.name overrides it",
                                schema = @Schema(type = "string"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Token invalid or expired",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping(ApiConstants.Auth.VERIFY_EMAIL)
    ResponseEntity<ApiResponse<AuthResponse>> verifyEmail(
            @Parameter(
                            description =
                                    "One-time email verification token from the verification link",
                            required = true)
                    @RequestParam("token")
                    String token,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /**
     * Records a verification mail event when an account exists for the supplied address. Always
     * returns 200 to prevent account enumeration.
     */
    @Operation(
            summary = "Resend verification email",
            description =
                    "Records a verification mail event. Always returns 200 to prevent account"
                            + " enumeration.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Email dispatched (or silently ignored if address unknown)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.RESEND_VERIFY)
    ResponseEntity<ApiResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest httpRequest);

    /**
     * Records a password-reset mail event when an account exists. Always returns 200 to prevent
     * account enumeration.
     */
    @Operation(
            summary = "Request password reset",
            description =
                    "Records a password-reset mail event for the supplied email. Always returns"
                            + " 200 to prevent account enumeration.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Reset email dispatched (or silently ignored if address unknown)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @PostMapping(ApiConstants.Auth.FORGOT_PASSWORD)
    ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest);

    /** Consumes a password-reset token and replaces the user's password hash. */
    @Operation(
            summary = "Reset password",
            description =
                    "Consumes the one-time reset token and replaces the account's password hash.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Password updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation failure, or token invalid or expired",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Token was valid but the account is banned (AUTH_ACCOUNT_LOCKED), or"
                                + " suspended or deactivated (AUTH_ACCOUNT_INACTIVE)",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(ApiConstants.Auth.RESET_PASSWORD)
    ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest);

    /** Redeems a short-lived OAuth2 exchange code for an access/refresh token pair. */
    @Operation(
            summary = "Exchange OAuth2 code for tokens",
            description =
                    "Consumes the one-time exchange code issued by the OAuth2 success handler and"
                            + " returns a standard access/refresh token pair. The code is valid for"
                            + " 120 seconds and is deleted on first use.",
            security = {@SecurityRequirement(name = "")})
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Exchange successful — access + refresh tokens returned",
                headers =
                        @Header(
                                name = "Set-Cookie",
                                description =
                                        "HttpOnly refresh cookie carrying the refresh token, scoped"
                                                + " to /api/v1/auth. Named luvax_refresh unless"
                                                + " app.security.refresh-cookie.name overrides it",
                                schema = @Schema(type = "string"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation failure, or exchange code invalid or expired",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description =
                        "Code was valid but the account is banned (AUTH_ACCOUNT_LOCKED), or"
                                + " suspended or deactivated (AUTH_ACCOUNT_INACTIVE)",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(ApiConstants.Auth.OAUTH2_EXCHANGE)
    ResponseEntity<ApiResponse<AuthResponse>> exchangeOAuth2Code(
            @Valid @RequestBody OAuth2ExchangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse);

    /**
     * Issues a single-use ticket for authenticating a WebSocket handshake.
     *
     * <p>Requires an authenticated caller. The ticket redeems, exactly once and within 30 seconds,
     * to the access token presented here, so the token itself never travels in a handshake URL
     * where proxies and CDNs would record it in their access logs.
     *
     * @param httpRequest the current request, read for its bearer token
     * @return the issued ticket
     */
    @Operation(
            summary = "Issue a WebSocket handshake ticket",
            description =
                    "Returns a single-use ticket, valid for 30 seconds, to be supplied as the"
                            + " handshake query parameter named ticket. Requires authentication."
                            + " Redeeming a ticket twice fails, so a client requests a fresh one"
                            + " per connection and per reconnect.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Ticket issued"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "No bearer token was supplied, or it does not authenticate",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping(ApiConstants.Auth.WS_TICKET)
    ResponseEntity<ApiResponse<WebSocketTicketResponse>> issueWebSocketTicket(
            HttpServletRequest httpRequest);
}
