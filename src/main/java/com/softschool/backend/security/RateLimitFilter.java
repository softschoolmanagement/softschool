package com.softschool.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Global rate limiting for every public REST endpoint under /api/**.
 *
 * ── Why ──────────────────────────────────────────────────────────────────
 * OWASP API4:2023 (Unrestricted Resource Consumption): without a cap, a
 * single client - a script, a bot, a runaway retry loop, or just a heavy
 * user - can flood the server/database with requests. Every /api/** call
 * now gets a generous but finite per-IP budget.
 *
 * OWASP API2:2023 (Broken Authentication) / ASVS 2.2.1: login, school
 * registration and password-reset are prime credential-stuffing / brute
 * force targets, so on TOP of the general limit they get a much stricter
 * cap, enforced BOTH per-IP and per-account. Per-IP alone isn't enough
 * (an attacker can spray guesses across many accounts from one IP) and
 * per-account alone isn't enough either (an attacker can rotate IPs to
 * hammer one account) - so both are checked.
 *
 * ── What this does NOT change ───────────────────────────────────────────
 *  - No existing controller, model, or route is touched.
 *  - Only paths starting with "/api/" are limited; the static frontend
 *    (index.html, *.js, *.css, images) is served exactly as before.
 *  - Defaults are sized to the app's real traffic patterns (see below),
 *    including the CSV bulk-import flow in manage-students.js, which fires
 *    one POST /api/students per imported row in a normal user session -
 *    the general limit (300 requests/60s per IP by default) comfortably
 *    covers that without breaking the import.
 *
 * ── Response on limit exceeded ──────────────────────────────────────────
 * HTTP 429 Too Many Requests, with a Retry-After header (seconds) and a
 * small JSON body - never a stack trace or other internal detail, per
 * OWASP guidance on not leaking implementation info in error responses.
 *
 * ── Tuning ───────────────────────────────────────────────────────────────
 * All numbers below are overridable from application.properties without
 * touching this file, e.g.:
 *   ratelimit.general.capacity=300
 *   ratelimit.general.window-seconds=60
 *   ratelimit.auth.ip-capacity=20
 *   ratelimit.auth.ip-window-seconds=60
 *   ratelimit.auth.identity-capacity=7
 *   ratelimit.auth.identity-window-seconds=300
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── General baseline: every /api/** request, keyed by client IP ──
    @Value("${ratelimit.general.capacity:300}")
    private int generalCapacity;

    @Value("${ratelimit.general.window-seconds:60}")
    private int generalWindowSeconds;

    // ── Auth endpoints: stricter per-IP limit ──
    @Value("${ratelimit.auth.ip-capacity:20}")
    private int authIpCapacity;

    @Value("${ratelimit.auth.ip-window-seconds:60}")
    private int authIpWindowSeconds;

    // ── Auth endpoints: per-account limit (brute-force protection) ──
    @Value("${ratelimit.auth.identity-capacity:7}")
    private int authIdentityCapacity;

    @Value("${ratelimit.auth.identity-window-seconds:300}")
    private int authIdentityWindowSeconds;

    /**
     * Endpoints where the real risk is someone brute-forcing a specific
     * account (as opposed to just hammering the server), so they get the
     * stricter auth-ip + auth-identity limits on top of the general one.
     */
    private static final Set<String> SENSITIVE_PATHS = Set.of(
            "/api/auth/login",
            "/api/school/register",
            "/api/school/login",
            "/api/school/login-token",
            "/api/school/reset-password"
    );

    /** JSON body fields (checked in this order) that identify "which account" for the sensitive paths above. */
    private static final String[] IDENTITY_FIELDS = {"phone", "username", "schoolId"};

    @Autowired
    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Only guard the API. Static assets / anything else pass straight
        // through, unmodified.
        if (path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        // 1) Baseline per-IP limit - applies to every API call, sensitive
        //    or not, so nothing under /api/** is ever unlimited.
        RateLimiter.Decision general = rateLimiter.tryConsume(
                "general-ip", clientIp, generalCapacity, generalWindowSeconds * 1000L);
        if (!general.allowed) {
            reject(response, general);
            return;
        }

        HttpServletRequest downstreamRequest = request;

        // 2) Extra protection on login/register/reset-password.
        if (SENSITIVE_PATHS.contains(path)) {
            RateLimiter.Decision authIp = rateLimiter.tryConsume(
                    "auth-ip", clientIp, authIpCapacity, authIpWindowSeconds * 1000L);
            if (!authIp.allowed) {
                reject(response, authIp);
                return;
            }

            // Wrap so we can read the body here AND let the controller
            // still read it fully afterwards (see CachedBodyHttpServletRequest).
            CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
            downstreamRequest = cachedRequest;

            String identity = extractIdentity(cachedRequest);
            if (identity != null && !identity.isBlank()) {
                String identityKey = path + ":" + identity.trim().toLowerCase();
                RateLimiter.Decision authIdentity = rateLimiter.tryConsume(
                        "auth-identity", identityKey, authIdentityCapacity, authIdentityWindowSeconds * 1000L);
                if (!authIdentity.allowed) {
                    reject(response, authIdentity);
                    return;
                }
            }
        }

        // Within limits - let it through. Informational headers let
        // well-behaved clients back off before they ever hit a 429.
        response.setHeader("X-RateLimit-Limit", String.valueOf(general.limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(general.remaining));
        filterChain.doFilter(downstreamRequest, response);
    }

    /**
     * Best-effort look at the request body for a field that names an
     * account. Never throws / never fails the request - if the body is
     * missing or isn't valid JSON, we just skip per-account limiting for
     * this call and fall back to the per-IP limits above (the controller
     * will still reject a malformed body normally).
     */
    private String extractIdentity(CachedBodyHttpServletRequest request) {
        try {
            byte[] body = request.getCachedBody();
            if (body == null || body.length == 0) return null;
            JsonNode node = objectMapper.readTree(body);
            for (String field : IDENTITY_FIELDS) {
                if (node.hasNonNull(field)) {
                    return node.get(field).asText();
                }
            }
        } catch (Exception ignored) {
            // Malformed/unparseable body - not our concern here.
        }
        return null;
    }

    /**
     * Resolves the caller's IP address.
     *
     * Trusts the first hop of X-Forwarded-For, which is correct when this
     * backend sits behind a single reverse proxy / load balancer that sets
     * that header itself (the normal deployment shape for a Spring Boot
     * app). If this backend is ever exposed directly to the internet with
     * no trusted proxy in front of it, use request.getRemoteAddr() alone
     * instead - otherwise a client could spoof X-Forwarded-For to dodge
     * its own rate limit.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, RateLimiter.Decision decision) throws IOException {
        response.setStatus(429); // 429 Too Many Requests
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds));
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setContentType("application/json");
        // Generic message only - no internal details leaked in the error response.
        response.getWriter().write(
                "{\"error\":\"Too many requests. Please slow down and try again in "
                        + decision.retryAfterSeconds + " second(s).\"}"
        );
    }
}
