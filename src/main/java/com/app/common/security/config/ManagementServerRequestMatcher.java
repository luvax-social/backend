package com.app.common.security.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/** Matches requests served by the actuator management server rather than the application server. */
final class ManagementServerRequestMatcher implements RequestMatcher {

    static final String MANAGEMENT_NAMESPACE = "management";

    @Override
    public boolean matches(HttpServletRequest request) {
        WebApplicationContext context =
                WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        return context != null
                && WebServerApplicationContext.hasServerNamespace(context, MANAGEMENT_NAMESPACE);
    }
}
