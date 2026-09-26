package com.app.common.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletResponse;

import com.app.common.ApiConstants;
import com.app.common.config.redis.RateLimitProperties;
import com.app.common.config.redis.RateLimitProperties.Rule;
import com.app.common.config.security.SecurityProperties;
import com.app.common.security.service.RateLimiterService;
import com.app.common.security.util.IpExtractor;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

    private static final Rule LOW_TRAFFIC_RULE = new Rule(5, 60);
    private static final Rule FORGOT_RULE = new Rule(3, 300);
    private static final Rule LOGIN_RULE = new Rule(10, 900);
    private static final Rule RESEND_RULE = new Rule(3, 3600);

    private static final String REGISTER_PATH = ApiConstants.Auth.ROOT + ApiConstants.Auth.REGISTER;
    private static final String LOGIN_PATH = ApiConstants.Auth.ROOT + ApiConstants.Auth.LOGIN;
    private static final String REFRESH_PATH = ApiConstants.Auth.ROOT + ApiConstants.Auth.REFRESH;
    private static final String FORGOT_PATH =
            ApiConstants.Auth.ROOT + ApiConstants.Auth.FORGOT_PASSWORD;
    private static final String RESET_PATH =
            ApiConstants.Auth.ROOT + ApiConstants.Auth.RESET_PASSWORD;
    private static final String VERIFY_PATH =
            ApiConstants.Auth.ROOT + ApiConstants.Auth.VERIFY_EMAIL;
    private static final String RESEND_PATH =
            ApiConstants.Auth.ROOT + ApiConstants.Auth.RESEND_VERIFY;

    @Mock private RateLimiterService rateLimiterService;
    @Mock private IpExtractor ipExtractor;
    @Mock private HttpServletRequest request;
    @Mock private FilterChain chain;

    private RateLimitProperties properties;
    private AuthRateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties =
                new RateLimitProperties(
                        Map.of(
                                LOGIN_PATH, LOGIN_RULE,
                                FORGOT_PATH, FORGOT_RULE,
                                RESEND_PATH, RESEND_RULE,
                                REGISTER_PATH, LOW_TRAFFIC_RULE,
                                REFRESH_PATH, new Rule(30, 60),
                                RESET_PATH, new Rule(5, 300),
                                VERIFY_PATH, new Rule(10, 60)));
        SecurityProperties securityProperties =
                new SecurityProperties(
                        java.util.List.of(), 2048, "test-cookie-signing-secret-placeholder-32ch");
        filter =
                new AuthRateLimitFilter(
                        rateLimiterService,
                        properties,
                        objectMapper,
                        ipExtractor,
                        securityProperties);
    }

    @Test
    void isRateLimited_register_post_returnsTrue() {
        assertThat(filter.isRateLimited(REGISTER_PATH, "POST")).isTrue();
    }

    @Test
    void isRateLimited_refresh_post_returnsTrue() {
        assertThat(filter.isRateLimited(REFRESH_PATH, "POST")).isTrue();
    }

    @Test
    void isRateLimited_resetPassword_post_returnsTrue() {
        assertThat(filter.isRateLimited(RESET_PATH, "POST")).isTrue();
    }

    @Test
    void isRateLimited_verifyEmail_get_returnsTrue() {
        assertThat(filter.isRateLimited(VERIFY_PATH, "GET")).isTrue();
    }

    @Test
    void isRateLimited_login_post_returnsTrue() {
        assertThat(filter.isRateLimited(LOGIN_PATH, "POST")).isTrue();
    }

    @Test
    void isRateLimited_forgotPassword_post_returnsTrue() {
        assertThat(filter.isRateLimited(FORGOT_PATH, "POST")).isTrue();
    }

    @Test
    void isRateLimited_resendVerify_post_returnsTrue() {
        assertThat(filter.isRateLimited(RESEND_PATH, "POST")).isTrue();
    }

    @Test
    void isRateLimited_logout_post_returnsFalse() {
        String logoutPath = ApiConstants.Auth.ROOT + ApiConstants.Auth.LOGOUT;
        assertThat(filter.isRateLimited(logoutPath, "POST")).isFalse();
    }

    @Test
    void isRateLimited_usersMePath_get_returnsFalse() {
        String mePath = ApiConstants.Users.ROOT + ApiConstants.Users.ME;
        assertThat(filter.isRateLimited(mePath, "GET")).isFalse();
    }

    @Test
    void doFilterInternal_limitExceeded_writesRetryAfterHeader() throws Exception {
        when(request.getRequestURI()).thenReturn(FORGOT_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(ipExtractor.extract(any())).thenReturn("1.2.3.4");
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        String retryAfter = response.getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isGreaterThan(0);
    }

    @Test
    void doFilterInternal_limitExceeded_forRegister_writesRetryAfterHeader() throws Exception {
        when(request.getRequestURI()).thenReturn(REGISTER_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(ipExtractor.extract(any())).thenReturn("1.2.3.4");
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        String retryAfter = response.getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isGreaterThan(0);
    }

    @Test
    void doFilterInternal_underLimit_proceedsToChain() throws Exception {
        when(request.getRequestURI()).thenReturn(FORGOT_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(ipExtractor.extract(any())).thenReturn("1.2.3.4");
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void
            doFilterInternal_loginPath_unexpectedBodyReadFailure_writesInternalErrorResponseInsteadOfPropagating()
                    throws Exception {
        when(request.getRequestURI()).thenReturn(LOGIN_PATH);
        when(request.getMethod()).thenReturn("POST");
        ServletInputStream brokenStream =
                new ServletInputStream() {
                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener readListener) {}

                    @Override
                    public int read() throws IOException {
                        throw new IOException("Connection reset by peer");
                    }
                };
        when(request.getInputStream()).thenReturn(brokenStream);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("INTERNAL_ERROR");
    }

    @Test
    void resolveRule_antPatternMatch_returnsRule() {
        // Configure a wildcard rule for post sub-paths.
        Rule postRule = new Rule(50, 60);
        RateLimitProperties patternProps =
                new RateLimitProperties(
                        Map.of("/api/v1/posts/**", postRule, LOGIN_PATH, LOGIN_RULE));
        SecurityProperties securityProperties =
                new SecurityProperties(
                        java.util.List.of(), 2048, "test-cookie-signing-secret-placeholder-32ch");
        AuthRateLimitFilter patternFilter =
                new AuthRateLimitFilter(
                        rateLimiterService,
                        patternProps,
                        objectMapper,
                        ipExtractor,
                        securityProperties);

        // Exact auth path still resolves.
        assertThat(patternFilter.resolveRule(LOGIN_PATH, "POST")).isEqualTo(LOGIN_RULE);
        // Concrete path under the wildcard pattern resolves.
        assertThat(patternFilter.resolveRule("/api/v1/posts/abc123/likes", "POST"))
                .isEqualTo(postRule);
        // Unmatched path returns null.
        assertThat(patternFilter.resolveRule("/api/v1/users/me", "GET")).isNull();
    }

    @Test
    void resolveRule_administrativeSubTree_coversEveryPathUnderIt() {
        // The administrative surface carried no per-caller rule at all, including its 33
        // path-variable routes. One sub-tree pattern covers all of them, which is only possible
        // because the filter falls back to Ant matching.
        Rule adminRule = new Rule(300, 60);
        AuthRateLimitFilter adminFilter =
                filterWith(new RateLimitProperties(Map.of("/api/v1/admin/**", adminRule)));

        assertThat(adminFilter.resolveRule("/api/v1/admin/users", "GET")).isEqualTo(adminRule);
        assertThat(
                        adminFilter.resolveRule(
                                "/api/v1/admin/users/6f1d3d1c-0d4a-4a3f-8f2b-2c4a9b7e1a55/ban",
                                "PATCH"))
                .isEqualTo(adminRule);
        assertThat(adminFilter.resolveRule("/api/v1/admin/actions", "GET")).isEqualTo(adminRule);
        assertThat(adminFilter.resolveRule("/api/v1/posts", "POST")).isNull();
    }

    @Test
    void resolveRule_overlappingPatterns_theMostSpecificOneWins() {
        // Declaration order decides map iteration order, so resolving on first match would make an
        // endpoint's budget depend on where somebody happened to add its rule in the YAML file.
        Rule broad = new Rule(300, 60);
        Rule narrow = new Rule(30, 60);
        Map<String, Rule> declaredBroadFirst = new java.util.LinkedHashMap<>();
        declaredBroadFirst.put("/api/v1/admin/**", broad);
        declaredBroadFirst.put("/api/v1/admin/stats/**", narrow);
        Map<String, Rule> declaredNarrowFirst = new java.util.LinkedHashMap<>();
        declaredNarrowFirst.put("/api/v1/admin/stats/**", narrow);
        declaredNarrowFirst.put("/api/v1/admin/**", broad);

        assertThat(
                        filterWith(new RateLimitProperties(declaredBroadFirst))
                                .resolveRule("/api/v1/admin/stats/timeseries", "GET"))
                .isEqualTo(narrow);
        assertThat(
                        filterWith(new RateLimitProperties(declaredNarrowFirst))
                                .resolveRule("/api/v1/admin/stats/timeseries", "GET"))
                .isEqualTo(narrow);
        assertThat(
                        filterWith(new RateLimitProperties(declaredNarrowFirst))
                                .resolveRule("/api/v1/admin/users", "GET"))
                .isEqualTo(broad);
    }

    @Test
    void resolveRule_recommendationsSubTree_coversFeedAndIsOverriddenByImpressionsLiteral() {
        // The recommendation surface carried no per-caller rule at all until now. One sub-tree
        // pattern covers /feed; the literal impressions path resolves ahead of it via the same
        // exact-match-first, most-specific-pattern-next resolution the admin rules use.
        Rule subTreeRule = new Rule(60, 60);
        Rule impressionsRule = new Rule(30, 60);
        Map<String, Rule> rules = new java.util.LinkedHashMap<>();
        rules.put("/api/v1/recommendations/**", subTreeRule);
        rules.put("/api/v1/recommendations/impressions", impressionsRule);
        AuthRateLimitFilter recommendationsFilter = filterWith(new RateLimitProperties(rules));

        assertThat(recommendationsFilter.resolveRule("/api/v1/recommendations/feed", "GET"))
                .isEqualTo(subTreeRule);
        assertThat(recommendationsFilter.resolveRule("/api/v1/recommendations/impressions", "POST"))
                .isEqualTo(impressionsRule);
        assertThat(recommendationsFilter.resolveRule("/api/v1/posts", "GET")).isNull();
    }

    @Test
    void resolveRule_supportTicketSubTree_coversTheCollectionAndThePathVariableRoute() {
        // The authenticated ticket sub-tree carried only the JVM-wide Resilience4j backstop, which
        // is a global circuit and not a per-caller bound. Both routes must resolve: the literal
        // collection through the exact-match fast path, and /tickets/{ticketId} through the
        // sub-tree pattern.
        Rule ticketsRule = new Rule(10, 3600);
        Map<String, Rule> rules = new java.util.LinkedHashMap<>();
        rules.put("/api/v1/support/tickets", ticketsRule);
        rules.put("/api/v1/support/tickets/**", ticketsRule);
        AuthRateLimitFilter supportFilter = filterWith(new RateLimitProperties(rules));

        assertThat(supportFilter.resolveRule("/api/v1/support/tickets", "POST"))
                .isEqualTo(ticketsRule);
        assertThat(supportFilter.resolveRule("/api/v1/support/tickets", "GET"))
                .isEqualTo(ticketsRule);
        assertThat(
                        supportFilter.resolveRule(
                                "/api/v1/support/tickets/6f1d3d1c-0d4a-4a3f-8f2b-2c4a9b7e1a55",
                                "GET"))
                .isEqualTo(ticketsRule);
        assertThat(supportFilter.resolveRule("/api/v1/support/appeal", "POST")).isNull();
    }

    private AuthRateLimitFilter filterWith(RateLimitProperties rules) {
        return new AuthRateLimitFilter(
                rateLimiterService,
                rules,
                objectMapper,
                ipExtractor,
                new SecurityProperties(
                        java.util.List.of(), 2048, "test-cookie-signing-secret-placeholder-32ch"));
    }

    @Test
    void doFilterInternal_patternMatchedPaths_shareSameBucketKey() throws Exception {
        // Two different concrete paths that both match the same wildcard rule, e.g. two distinct
        // SockJS transport negotiation URLs under /ws/**, must bucket together on the matched
        // pattern rather than each getting its own fresh bucket keyed on the concrete path.
        RateLimitProperties wsProps = new RateLimitProperties(Map.of("/ws/**", new Rule(30, 60)));
        SecurityProperties securityProperties =
                new SecurityProperties(
                        java.util.List.of(), 2048, "test-cookie-signing-secret-placeholder-32ch");
        AuthRateLimitFilter wsFilter =
                new AuthRateLimitFilter(
                        rateLimiterService, wsProps, objectMapper, ipExtractor, securityProperties);

        when(ipExtractor.extract(any())).thenReturn("9.9.9.9");
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");

        when(request.getRequestURI()).thenReturn("/ws/comments/server1/session1/websocket");
        wsFilter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        when(request.getRequestURI()).thenReturn("/ws/comments/server2/session2/websocket");
        wsFilter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterService, times(2)).isAllowed(keyCaptor.capture(), anyInt(), anyLong());
        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
        assertThat(keys.get(0)).contains("/ws/**");
    }

    @Test
    void doFilterInternal_exactMatchRule_keepsPathAsBucketKey() throws Exception {
        // Existing exact-match auth rules must keep bucketing on the concrete path: for an exact
        // match, matchedKey equals path, so the key is the path plus the IP, with no method.
        when(request.getRequestURI()).thenReturn(FORGOT_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(ipExtractor.extract(any())).thenReturn("5.5.5.5");
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterService).isAllowed(keyCaptor.capture(), anyInt(), anyLong());
        assertThat(keyCaptor.getValue()).isEqualTo(FORGOT_PATH + ":5.5.5.5");
    }

    @Test
    void differentMethodsSamePath_shareOneBucket() throws Exception {
        // A rule's configured budget is for the endpoint as a whole. GET and POST reaching the
        // same matched key (e.g. /api/v1/conversations, or SockJS handshake vs xhr transport under
        // /ws/**) must consume the same bucket rather than each getting its own, which would
        // silently double the effective allowance.
        RateLimitProperties conversationsProps =
                new RateLimitProperties(Map.of("/api/v1/conversations", new Rule(30, 60)));
        SecurityProperties securityProperties =
                new SecurityProperties(
                        java.util.List.of(), 2048, "test-cookie-signing-secret-placeholder-32ch");
        AuthRateLimitFilter conversationsFilter =
                new AuthRateLimitFilter(
                        rateLimiterService,
                        conversationsProps,
                        objectMapper,
                        ipExtractor,
                        securityProperties);

        when(ipExtractor.extract(any())).thenReturn("7.7.7.7");
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(request.getRequestURI()).thenReturn("/api/v1/conversations");

        when(request.getMethod()).thenReturn("GET");
        conversationsFilter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        when(request.getMethod()).thenReturn("POST");
        conversationsFilter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterService, times(2)).isAllowed(keyCaptor.capture(), anyInt(), anyLong());
        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    @Test
    void doFilterInternal_loginBodyBetween2048And4096Bytes_isAccepted() throws Exception {
        // 2048 was the cap before the auth surfaces took a turnstileToken field. A Turnstile token
        // runs to roughly 2048 characters on its own, so a legitimate login payload now lands in
        // this range and must not be refused as oversized.
        String body = loginBodyOfAtLeast(3000);
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isBetween(2049, 4096);

        MockHttpServletResponse response = bodyLimitedLoginRequest(body);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void doFilterInternal_loginBodyAbove4096Bytes_isStillRefused() throws Exception {
        String body = loginBodyOfAtLeast(5000);
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4096);

        MockHttpServletResponse response = bodyLimitedLoginRequest(body);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("BAD_REQUEST");
        verify(chain, never()).doFilter(any(), any());
    }

    private static String loginBodyOfAtLeast(int tokenChars) {
        return "{\"identifier\":\"a@b.c\",\"password\":\"p\",\"turnstileToken\":\""
                + "t".repeat(tokenChars)
                + "\"}";
    }

    /** Drives one POST to the login path through a filter carrying the production 4096 cap. */
    private MockHttpServletResponse bodyLimitedLoginRequest(String body) throws Exception {
        AuthRateLimitFilter cappedFilter =
                new AuthRateLimitFilter(
                        rateLimiterService,
                        new RateLimitProperties(Map.of(LOGIN_PATH, LOGIN_RULE)),
                        objectMapper,
                        ipExtractor,
                        new SecurityProperties(
                                java.util.List.of(),
                                4096,
                                "test-cookie-signing-secret-placeholder-32ch"));
        when(request.getRequestURI()).thenReturn(LOGIN_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getInputStream())
                .thenReturn(
                        new DelegatingServletInputStream(
                                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        // Under the cap the request reaches the limiter, which must not be what refuses it here.
        lenient()
                .when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong()))
                .thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        cappedFilter.doFilterInternal(request, response, chain);
        return response;
    }
}
