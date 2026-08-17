package com.softschool.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guarantees every response — success AND error — carries CORS headers.
 *
 * ── The bug this fixes ─────────────────────────────────────────────────
 * WebConfig's addCorsMappings(...) only applies to requests that reach
 * Spring MVC's DispatcherServlet. RateLimitFilter, AdminAuthFilter and
 * SchoolAuthFilter are plain servlet Filters that run BEFORE the
 * DispatcherServlet, and each of them can short-circuit a request (429,
 * 401, 403) by writing directly to the response and returning — never
 * reaching the point where WebConfig's CORS handling would normally add
 * `Access-Control-Allow-Origin`.
 *
 * The browser refuses to hand JS the real status code (401, 403, 429...)
 * of a cross-origin response that's missing that header — it reports the
 * whole thing as a generic, indistinguishable network/CORS failure
 * instead. That's what turns "your session expired" into a confusing
 * "the server could not be reached" in the frontend.
 *
 * ── The fix ─────────────────────────────────────────────────────────────
 * Run this filter FIRST (lower @Order than every other filter below),
 * stamp the CORS headers unconditionally, and answer OPTIONS preflight
 * requests directly. Every other filter still runs after this and is
 * free to reject the request — but now the rejection is visible to the
 * frontend as the real status code instead of a masked CORS error.
 *
 * Mirrors the existing WebConfig policy (allow any origin, no credentials)
 * so behavior for successful requests is unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "*");
        // Cache the (harmless, always-the-same) preflight answer so browsers
        // don't have to re-run OPTIONS before every single request.
        response.setHeader("Access-Control-Max-Age", "3600");

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
