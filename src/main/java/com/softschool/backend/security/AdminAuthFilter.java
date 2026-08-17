package com.softschool.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guards every /api/admin/** call (school creation/deletion, plan
 * management — the most destructive endpoints in the app) behind a valid
 * signed session token minted by AdminAuthController.
 *
 * Previously these endpoints had no authentication at all — SuperAdminController
 * was reachable by anyone who found the URL. This filter closes that gap
 * without requiring any static key to ever live in the frontend JS: the
 * browser only ever holds a short-lived token obtained via
 * /api/admin-auth/login (see AdminSessionService for why that's safe to
 * keep client-side while the actual signing secret is not).
 *
 * Runs after CorsHeaderFilter and RateLimitFilter so brute-forcing logins
 * is already throttled (and every response already carries CORS headers)
 * before this even checks the token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final AdminSessionService sessionService;

    @Autowired
    public AdminAuthFilter(AdminSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        boolean isAdminApi = path != null && path.startsWith("/api/admin/");
        if (!isAdminApi) {
            filterChain.doFilter(request, response);
            return;
        }

        // CORS preflight requests carry no Authorization header — let them
        // through so the browser can complete the preflight; the actual
        // request that follows is still checked normally.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
                ? header.substring("Bearer ".length())
                : null;

        String username = sessionService.verifyToken(token);
        if (username == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or expired admin session. Please log in again.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
