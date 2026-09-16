package com.app.common.security.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.app.common.ApiConstants;
import com.app.common.enums.ApiErrorCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.filter.AuthRateLimitFilter;
import com.app.common.security.filter.JwtAuthenticationFilter;
import com.app.common.security.jwt.JwtProperties;
import com.app.modules.auth.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.app.modules.auth.oauth2.CustomOidcUserService;
import com.app.modules.auth.oauth2.OAuth2AuthenticationFailureHandler;
import com.app.modules.auth.oauth2.OAuth2AuthenticationSuccessHandler;

import tools.jackson.databind.ObjectMapper;

/**
 * Stateless Spring Security wiring for the modular monolith.
 *
 * <p>Declares exactly one {@link SecurityFilterChain} bean: the JWT-based REST chain that disables
 * sessions, registers {@link JwtAuthenticationFilter}, and routes authentication and authorization
 * failures back through the {@link ApiResponse} envelope. Authorization rules are organized by
 * semantic category via private helper methods invoked from {@link #securityFilterChain}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(proxyTargetClass = true)
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
public class SecurityConfig {

    private static final String[] PUBLIC_POST_AUTH_PATHS = {
        ApiConstants.Auth.ROOT + ApiConstants.Auth.REGISTER,
        ApiConstants.Auth.ROOT + ApiConstants.Auth.LOGIN,
        ApiConstants.Auth.ROOT + ApiConstants.Auth.REFRESH,
        ApiConstants.Auth.ROOT + ApiConstants.Auth.FORGOT_PASSWORD,
        ApiConstants.Auth.ROOT + ApiConstants.Auth.RESET_PASSWORD,
        ApiConstants.Auth.ROOT + ApiConstants.Auth.RESEND_VERIFY,
    };

    private static final String[] AUTHENTICATED_POST_AUTH_PATHS = {
        ApiConstants.Auth.ROOT + ApiConstants.Auth.LOGOUT,
    };

    /**
     * The metrics endpoint, opened to anonymous callers only when {@code
     * app.security.public-metrics-endpoint} is set.
     *
     * <p>It publishes every routed URI template, per-endpoint request counts and latencies, and
     * JVM, pool and broker internals - tens of kilobytes of reconnaissance material, though no
     * secret or token. It was previously anonymous unconditionally, on the reasoning that a
     * Prometheus scraper cannot present an admin JWT. That reasoning is sound but incomplete: it
     * opened the endpoint to everyone rather than to the scraper, and unlike {@code
     * trusted-proxy-cidrs} nothing said so at startup, so a deployment that never added the ingress
     * rule had no signal at all.
     *
     * <p>It now defaults closed and falls through to the ADMIN rule below. Set the property only
     * where the port this application listens on is unreachable from outside the monitoring network
     * - a separate management port or an ingress rule is the safer arrangement.
     */
    private static final String METRICS_PATH = "/actuator/prometheus";

    private static final String[] PUBLIC_INFRA_PATHS = {
        "/actuator/health",
        "/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/ws/messages/**",
        "/error",
    };

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    // Read here rather than through SecurityProperties because it is consumed once, while the
    // filter chain is being built, and nothing outside this class has any use for it.
    @Value("${app.security.public-metrics-endpoint:false}")
    private boolean publicMetricsEndpoint;

    private final JwtProperties jwtProperties;
    private final CorsProperties corsProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final CustomOidcUserService customOidcUserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final CookieOAuth2AuthorizationRequestRepository
            cookieOAuth2AuthorizationRequestRepository;

    @Autowired private ObjectMapper objectMapper;

    public SecurityConfig(
            JwtProperties jwtProperties,
            CorsProperties corsProperties,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthRateLimitFilter authRateLimitFilter,
            CustomOidcUserService customOidcUserService,
            OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
            OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler,
            CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository) {
        this.jwtProperties = jwtProperties;
        this.corsProperties = corsProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.customOidcUserService = customOidcUserService;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
        this.cookieOAuth2AuthorizationRequestRepository =
                cookieOAuth2AuthorizationRequestRepository;
    }

    @PostConstruct
    void validateJwtSecret() {
        String secret = jwtProperties.secret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 characters; refusing to start with a weak"
                            + " key");
        }
    }

    @Bean
    // API calls use bearer tokens, while the path-scoped refresh and OAuth cookies are HttpOnly
    // and SameSite=Lax. CSRF remains enabled for browser-facing paths outside these matchers.
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(
                        csrf ->
                                csrf.ignoringRequestMatchers(
                                        "/api/**",
                                        "/ws/comments/**",
                                        "/ws/notifications/**",
                                        "/ws/posts/**"))
                .sessionManagement(
                        sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(
                        auth -> {
                            configurePublicAuthEndpoints(auth);
                            configureOAuth2Endpoints(auth);
                            configureAuthenticatedEndpoints(auth);
                            configureInfrastructureEndpoints(auth);
                            configureRoleBasedEndpoints(auth);
                            configurePublicUsersEndpoints(auth);
                            configurePublicHashtagEndpoints(auth);
                            configureWebSocketEndpoints(auth);
                            auth.anyRequest().authenticated();
                        })
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class)
                .oauth2Login(
                        oauth2 ->
                                oauth2.authorizationEndpoint(
                                                ep ->
                                                        ep.baseUri("/api/v1/auth/oauth2/authorize")
                                                                .authorizationRequestRepository(
                                                                        cookieOAuth2AuthorizationRequestRepository))
                                        .redirectionEndpoint(
                                                ep -> ep.baseUri("/api/v1/auth/oauth2/callback/*"))
                                        .userInfoEndpoint(
                                                info -> info.oidcUserService(customOidcUserService))
                                        .successHandler(oAuth2AuthenticationSuccessHandler)
                                        .failureHandler(oAuth2AuthenticationFailureHandler))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(authenticationEntryPoint())
                                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    /** Permits anonymous access to token-issuing and account-management auth endpoints. */
    private void configurePublicAuthEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        auth.requestMatchers(HttpMethod.POST, PUBLIC_POST_AUTH_PATHS).permitAll();
        auth.requestMatchers(
                        HttpMethod.GET, ApiConstants.Auth.ROOT + ApiConstants.Auth.VERIFY_EMAIL)
                .permitAll();
    }

    /** Permits anonymous access to Spring OAuth2 client initiation and callback paths. */
    private void configureOAuth2Endpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        auth.requestMatchers(HttpMethod.GET, ApiConstants.Auth.ROOT + "/oauth2/**").permitAll();
        auth.requestMatchers(HttpMethod.POST, ApiConstants.Auth.ROOT + "/oauth2/**").permitAll();
    }

    /** Restricts session-scoped auth operations to authenticated users. */
    private void configureAuthenticatedEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        auth.requestMatchers(HttpMethod.POST, AUTHENTICATED_POST_AUTH_PATHS).authenticated();
        // Must be registered before the users permitAll block: the `/{userId}` template matches any
        // single segment, "search" included, and the first matching rule wins. Without this the
        // search endpoint would silently become anonymous, which is the one property its
        // enumeration bound depends on.
        auth.requestMatchers(HttpMethod.GET, ApiConstants.Users.ROOT + ApiConstants.Users.SEARCH)
                .authenticated();
    }

    /**
     * Opens health, API-documentation, and the error-dispatch endpoints to all callers; restricts
     * remaining actuator endpoints to ADMIN.
     *
     * <p>{@code /error} must be reachable regardless of the original request's authentication
     * state: Spring's ERROR dispatch (used when a {@code HandlerExceptionResolver} falls through to
     * {@code response.sendError(...)}) re-enters this filter chain as a fresh, unauthenticated
     * request. Without this rule that dispatch was itself rejected with 401, masking the original
     * status - most visibly turning a 406 content-negotiation failure into a 401 that looks like an
     * expired session.
     */
    private void configureInfrastructureEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        // The three anonymous support paths, and the campaign unsubscribe link.
        //
        // Anonymous by necessity rather than by convenience. TokenPrincipalResolverImpl admits only
        // ACTIVE accounts, so a banned or suspended user cannot authenticate at all, and they are
        // exactly the population an appeal path exists for. Each carries its own control instead of
        // a session: a single-use Redis token for the appeal and the confirmation, Turnstile plus
        // email confirmation for the public form, and a per-user stored secret for the unsubscribe
        // link. None of them issues a session, a token pair or a refresh token row.
        //
        // Each also carries its own entry in app.rate-limit.endpoint-rules in all three profiles.
        auth.requestMatchers(
                        HttpMethod.POST,
                        ApiConstants.Support.ROOT + ApiConstants.Support.APPEAL,
                        ApiConstants.Support.ROOT + ApiConstants.Support.PUBLIC_TICKET,
                        ApiConstants.Support.ROOT + ApiConstants.Support.CONFIRM,
                        ApiConstants.Support.ROOT + ApiConstants.Support.APPEAL_RESEND,
                        ApiConstants.Support.ROOT + ApiConstants.Support.UNSUBSCRIBE)
                .permitAll();

        // The public form's category list, read before the form can be filled in. GET only, and it
        // answers strictly less than the authenticated config vocabulary: enabled, public-form
        // support categories, which is display metadata and no account data.
        auth.requestMatchers(
                        HttpMethod.GET,
                        ApiConstants.Support.ROOT + ApiConstants.Support.PUBLIC_CATEGORIES)
                .permitAll();

        // Whether an appeal link is still redeemable, read without redeeming it. Anonymous for the
        // same reason the appeal POST is: the account it concerns is banned or suspended and cannot
        // authenticate. It widens nothing - a caller must already hold a 32-byte random token, and
        // it answers only the appeal category, which the form itself would have shown them. It
        // exists so the landing screen can refuse a dead link before the reader writes their
        // appeal instead of after, and it must never consume the token.
        auth.requestMatchers(
                        HttpMethod.GET,
                        ApiConstants.Support.ROOT + ApiConstants.Support.APPEAL_VALIDATE)
                .permitAll();

        // One appeal, read by the appellant who filed it and holds no session. Anonymous for the
        // same reason every other route here is: the account is banned or suspended and cannot
        // authenticate. The appeal token was spent by the redemption that created the ticket, so
        // this token is the only thing that can reach it afterwards. It widens nothing - the
        // caller must already hold a 32-byte random token - and it answers the owner-facing shape,
        // which structurally carries no internal note, assignee or escalation reason. Read-only:
        // it must never consume the token, because the link is meant to be followed repeatedly.
        auth.requestMatchers(
                        HttpMethod.GET,
                        ApiConstants.Support.ROOT + ApiConstants.Support.APPEAL_STATUS)
                .permitAll();

        auth.requestMatchers(PUBLIC_INFRA_PATHS).permitAll();
        // First matching rule wins, so this has to precede the ADMIN rule to have any effect.
        // Absent the opt-in, the metrics endpoint falls through to that rule like every other
        // actuator endpoint.
        if (publicMetricsEndpoint) {
            log.warn(
                    "app.security.public-metrics-endpoint is set: {} is served to any caller that"
                            + " can reach this port, with no authentication. It publishes the full"
                            + " routed endpoint surface, request counts and JVM internals. Leave"
                            + " this unset unless the port is reachable only from the monitoring"
                            + " network.",
                    METRICS_PATH);
            auth.requestMatchers(METRICS_PATH).permitAll();
        }
        auth.requestMatchers("/actuator/**").hasRole("ADMIN");
    }

    /**
     * Restricts account-status administration to ADMIN and the remaining moderation surfaces to
     * MODERATOR or ADMIN.
     *
     * <p>The two admin matchers are order-dependent: the narrower {@code /api/v1/admin/users/**}
     * rule must be registered first, because the first matching rule wins and the broader rule
     * below would otherwise grant a moderator the account-status endpoints. A moderator holding
     * those endpoints can ban an administrator, and a banned administrator cannot authenticate to
     * reverse it, so the role hierarchy inverts with no in-application recovery path. No endpoint
     * that a moderator legitimately needs may live under {@code /api/v1/admin/users/}.
     */
    private void configureRoleBasedEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        // The collection path is listed alongside the sub-tree pattern rather than relying on
        // "/users/**" also matching zero trailing segments. It does under both of Spring's matcher
        // implementations, but the ADMIN-only guarantee for the account list should not rest
        // on that detail surviving a future matcher change.
        auth.requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasRole("ADMIN");
        // Mail campaigns are administrator-only, and this must be registered before the broad
        // "/api/v1/admin/**" rule below, which admits a moderator. MailCampaignServiceImpl enforces
        // the same narrowing independently, so neither gate is load-bearing alone.
        auth.requestMatchers("/api/v1/admin/mail/**").hasRole("ADMIN");
        auth.requestMatchers("/api/v1/admin/**").hasAnyRole("MODERATOR", "ADMIN");
        auth.requestMatchers(
                        HttpMethod.GET,
                        ApiConstants.Reports.ROOT,
                        ApiConstants.Reports.ROOT + "/**")
                .hasAnyRole("MODERATOR", "ADMIN");
        auth.requestMatchers(
                        HttpMethod.PATCH, ApiConstants.Reports.ROOT + ApiConstants.Reports.STATUS)
                .hasAnyRole("MODERATOR", "ADMIN");
    }

    /** Permits unauthenticated access to public user profile lookups. */
    private void configurePublicUsersEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        auth.requestMatchers(
                        HttpMethod.GET,
                        ApiConstants.Users.ROOT + ApiConstants.Users.BY_ID,
                        ApiConstants.Users.ROOT + ApiConstants.Users.BY_USERNAME)
                .permitAll();
    }

    /** Permits unauthenticated access to hashtag fuzzy search and trending discovery endpoints. */
    private void configurePublicHashtagEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        auth.requestMatchers(
                        HttpMethod.GET,
                        ApiConstants.Hashtags.ROOT + ApiConstants.Hashtags.SEARCH,
                        ApiConstants.Hashtags.ROOT + ApiConstants.Hashtags.TRENDING)
                .permitAll();
    }

    // A browser cannot set an Authorization header on a native WebSocket handshake, so the JWT
    // rides as a query parameter instead; JwtHandshakeInterceptor is the sole authentication gate
    // for both paths, matching the query-parameter token it validates.
    private void configureWebSocketEndpoints(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        // Authentication for a STOMP endpoint happens in JwtHandshakeInterceptor, which reads
        // the token from the handshake query string; the filter chain must let the upgrade
        // request through for that interceptor to run at all.
        auth.requestMatchers("/ws/comments/**", "/ws/notifications/**", "/ws/posts/**").permitAll();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Refuse to fall back to a wildcard origin when allowCredentials=true. Empty config
        // becomes a deny-all CORS policy; operator must set CORS_ALLOWED_ORIGINS explicitly.
        configuration.setAllowedOrigins(corsProperties.allowedOriginList());
        configuration.setAllowedMethods(
                Arrays.asList(
                        HttpMethod.GET.name(),
                        HttpMethod.POST.name(),
                        HttpMethod.PUT.name(),
                        HttpMethod.PATCH.name(),
                        HttpMethod.DELETE.name(),
                        HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "Accept",
                        "Accept-Language",
                        "X-Requested-With",
                        "X-Device-ID",
                        "Idempotency-Key"));
        configuration.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER, "X-Total-Count"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (HttpServletRequest request,
                HttpServletResponse response,
                AuthenticationException ex) -> writeFailure(response, ApiErrorCode.UNAUTHORIZED);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request,
                HttpServletResponse response,
                AccessDeniedException ex) -> writeFailure(response, ApiErrorCode.FORBIDDEN);
    }

    private void writeFailure(HttpServletResponse response, ApiErrorCode errorCode)
            throws IOException, ServletException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(errorCode));
    }
}
