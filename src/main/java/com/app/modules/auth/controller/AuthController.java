package com.app.modules.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiErrorCode;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.exception.AppException;
import com.app.common.response.ApiResponse;
import com.app.common.security.service.RefreshTokenService;
import com.app.common.security.util.IpExtractor;
import com.app.modules.auth.api.AuthApi;
import com.app.modules.auth.cookie.RefreshTokenCookieManager;
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
import com.app.modules.auth.service.AuthService;
import com.app.modules.auth.service.WebSocketTicketService;

/** HTTP surface for email + password authentication flows. */
@RestController
public class AuthController extends BaseController implements AuthApi {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final RefreshTokenCookieManager refreshTokenCookieManager;
    private final WebSocketTicketService webSocketTicketService;
    private final RefreshTokenService refreshTokenService;
    private final IpExtractor ipExtractor;

    public AuthController(
            AuthService authService,
            RefreshTokenCookieManager refreshTokenCookieManager,
            WebSocketTicketService webSocketTicketService,
            RefreshTokenService refreshTokenService,
            IpExtractor ipExtractor) {
        this.authService = authService;
        this.refreshTokenCookieManager = refreshTokenCookieManager;
        this.webSocketTicketService = webSocketTicketService;
        this.refreshTokenService = refreshTokenService;
        this.ipExtractor = ipExtractor;
    }

    /**
     * Registers a new user and records verification + welcome mail events. No tokens are issued —
     * the user must verify their email before logging in.
     */
    @Override
    @PostMapping(ApiConstants.Auth.REGISTER)
    public ResponseEntity<ApiResponse<Void>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        authService.register(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED));
    }

    /**
     * Authenticates an existing user and returns access + refresh tokens. The refresh token is also
     * issued as an HttpOnly cookie so a browser client can restore the session after a page reload.
     */
    @Override
    @PostMapping(ApiConstants.Auth.LOGIN)
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResponse body = authService.login(request, httpRequest);
        refreshTokenCookieManager.write(httpResponse, body.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /**
     * Rotates the refresh token and returns a new session pair. The token is taken from the request
     * body when supplied, otherwise from the HttpOnly refresh cookie, so browser clients that hold
     * no token in memory can restore a session after a page reload.
     */
    @Override
    @PostMapping(ApiConstants.Auth.REFRESH)
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String rawRefreshToken =
                refreshTokenCookieManager.resolve(
                        request == null ? null : request.refreshToken(), httpRequest);
        AuthResponse body = authService.refresh(rawRefreshToken, httpRequest);
        refreshTokenCookieManager.write(httpResponse, body.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /**
     * Reports which session the calling client is using, resolved from the same refresh token the
     * rotation path reads.
     */
    @Override
    @PostMapping(ApiConstants.Auth.SESSION)
    public ResponseEntity<ApiResponse<CurrentSessionResponse>> currentSession(
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest) {
        String rawRefreshToken =
                refreshTokenCookieManager.resolve(
                        request == null ? null : request.refreshToken(), httpRequest);
        // An absent or unusable token is not an error here. The answer is "no session I can name",
        // and a client holding its refresh token outside the cookie path gets exactly that.
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        new CurrentSessionResponse(
                                refreshTokenService
                                        .findSessionIdByRawToken(rawRefreshToken)
                                        .orElse(null))));
    }

    /**
     * Revokes the supplied refresh token and clears the refresh cookie. Idempotent, including when
     * neither the body nor the cookie carries a token.
     */
    @Override
    @PostMapping(ApiConstants.Auth.LOGOUT)
    public ResponseEntity<Void> logout(
            @Valid @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String rawRefreshToken =
                refreshTokenCookieManager.resolve(
                        request == null ? null : request.refreshToken(), httpRequest);
        authService.logout(rawRefreshToken);
        refreshTokenCookieManager.clear(httpResponse);
        return ResponseEntity.noContent().build();
    }

    /**
     * Verifies an email address using the token embedded in the verification link and issues a
     * session, including the HttpOnly refresh cookie.
     */
    @Override
    @GetMapping(ApiConstants.Auth.VERIFY_EMAIL)
    public ResponseEntity<ApiResponse<AuthResponse>> verifyEmail(
            @RequestParam("token") String token,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResponse body = authService.verifyEmail(token, httpRequest);
        refreshTokenCookieManager.write(httpResponse, body.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /**
     * Records a verification mail event when an account exists for the supplied address. Always
     * returns 200 to prevent account enumeration.
     */
    @Override
    @PostMapping(ApiConstants.Auth.RESEND_VERIFY)
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest httpRequest) {
        authService.resendVerification(request, ipExtractor.extract(httpRequest));
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /**
     * Records a password-reset mail event when an account exists. Always returns 200 to prevent
     * account enumeration.
     */
    @Override
    @PostMapping(ApiConstants.Auth.FORGOT_PASSWORD)
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        authService.forgotPassword(request, ipExtractor.extract(httpRequest));
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Consumes a password-reset token and replaces the user's password hash. */
    @Override
    @PostMapping(ApiConstants.Auth.RESET_PASSWORD)
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        authService.resetPassword(request, ipExtractor.extract(httpRequest));
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /**
     * Redeems a short-lived OAuth2 exchange code for an access/refresh token pair and issues the
     * HttpOnly refresh cookie.
     */
    @Override
    @PostMapping(ApiConstants.Auth.OAUTH2_EXCHANGE)
    public ResponseEntity<ApiResponse<AuthResponse>> exchangeOAuth2Code(
            @Valid @RequestBody OAuth2ExchangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResponse body = authService.exchangeOAuth2Code(request, httpRequest);
        refreshTokenCookieManager.write(httpResponse, body.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    @Override
    @PostMapping(ApiConstants.Auth.WS_TICKET)
    public ResponseEntity<ApiResponse<WebSocketTicketResponse>> issueWebSocketTicket(
            HttpServletRequest httpRequest) {
        // Reads the bearer token directly rather than resolving the principal and re-minting: the
        // ticket must redeem to this exact token so the revocation sweep re-checks the same
        // credential the caller is actually holding.
        String header = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new AppException(ApiErrorCode.UNAUTHORIZED);
        }
        String ticket =
                webSocketTicketService.issueTicket(header.substring(BEARER_PREFIX.length()));
        return ResponseEntity.ok(
                ApiResponse.success(ApiSuccessCode.OK, new WebSocketTicketResponse(ticket)));
    }
}
