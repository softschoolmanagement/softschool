package com.softschool.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for CORS. Runs FIRST so that responses produced by
 * the rate-limit / auth filters (429, 401, 403) still carry CORS headers and
 * the browser shows the real status instead of a masked network error.
 *
 * ── Policy ──────────────────────────────────────────────────────────────
 *  - Only origins listed in {@code app.allowed-origins} (env ALLOWED_ORIGINS,
 *    comma-separated, exact match incl. scheme + port) are ever echoed back.
 *  - There is NO wildcard. A literal "*" in the setting is ignored.
 *  - Requests with no Origin header (curl, the biometric sync agent, same-
 *    origin) are unaffected - CORS is a browser-only mechanism.
 *  - Preflight from a non-allowed origin gets 403 and no CORS headers.
 *  - Credentials (cookies) are NOT allowed: the API authenticates with an
 *    Authorization: Bearer header only, which is also why classic CSRF does
 *    not apply (browsers never attach that header automatically).
 *  - Methods and headers are an explicit list, not "*".
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorsHeaderFilter.class);

    private final Set<String> allowedOrigins;

    public CorsHeaderFilter(@Value("${app.allowed-origins:}") String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o)
                .filter(o -> !o.isEmpty())
                .filter(o -> {
                    if (o.equals("*")) {
                        log.warn("Ignoring wildcard '*' in app.allowed-origins - list exact origins instead.");
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedOrigins.isEmpty()) {
            log.warn("app.allowed-origins is empty: browsers on other origins will be blocked by CORS.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean preflight = "OPTIONS".equalsIgnoreCase(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null;

        if (origin == null) {
            filterChain.doFilter(request, response);
            return;
        }

        response.addHeader("Vary", "Origin");

        if (!allowedOrigins.contains(origin)) {
            if (preflight) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            // Non-preflight: let it through without CORS headers; the browser
            // will refuse to expose the response to the disallowed page.
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Expose-Headers", "Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining");

        if (preflight) {
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Biometric-Key");
            response.setHeader("Access-Control-Max-Age", "3600");
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
