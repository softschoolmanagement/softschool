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
 * Keeps PARENT tokens inside /api/parent/**.
 *
 * WHY THIS MATTERS: a parent token is a normal signed school session with the username
 * "parent:<phone>". Several existing controllers (messages, announcements, diary, teacher-tests...)
 * only ask "is this a teacher?" and treat EVERYONE ELSE as the school admin. Without this filter a
 * parent could therefore call, for example, GET /api/messages/threads or POST /api/messages/broadcast
 * and be treated as the principal. This filter turns every request that carries a parent token and is
 * not aimed at /api/parent/** into a 403, before any other controller or filter can see it.
 *
 * Order: after CorsHeaderFilter (HIGHEST_PRECEDENCE) and RateLimitFilter (+10), before SchoolAuthFilter (+20).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class ParentAccessFilter extends OncePerRequestFilter {

    private static final String PARENT_PREFIX = "parent:";

    @Autowired
    private SchoolSessionService sessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")
                || uri.equals("/api/parent") || uri.startsWith("/api/parent/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String h = request.getHeader("Authorization");
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
        SchoolSessionService.Principal p = sessionService.verifyToken(token);
        if (p != null && p.getUsername() != null && p.getUsername().startsWith(PARENT_PREFIX)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Parent accounts cannot access this resource.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
