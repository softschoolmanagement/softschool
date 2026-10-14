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
 * Adds defensive response headers to every reply.
 *
 *  - X-Content-Type-Options: nosniff   -> browser must trust our Content-Type
 *    (stops an uploaded file being "sniffed" into HTML/JS).
 *  - Content-Security-Policy            -> this is a JSON/file API, it never
 *    needs to run scripts, load frames or fetch anything. Even if a response
 *    were ever rendered as a page, nothing could execute.
 *  - X-Frame-Options / frame-ancestors  -> clickjacking protection.
 *  - Referrer-Policy, Permissions-Policy, Cross-Origin-Resource-Policy.
 *  - Strict-Transport-Security          -> only sent when the request really
 *    arrived over HTTPS (directly or via X-Forwarded-Proto from the proxy).
 *  - Cache-Control: no-store on /api/**  -> authenticated JSON is never kept
 *    in shared/browser caches. (File endpoints set their own Cache-Control.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; sandbox");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Resource-Policy", "cross-origin");

        boolean https = request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        if (https) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }

        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
