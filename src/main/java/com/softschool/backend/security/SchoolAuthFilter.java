package com.softschool.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Authentication and tenant-boundary filter for all school operational APIs.
 *
 * This intentionally runs before controllers: a request cannot reach a
 * student/staff/finance/attendance/biometric/settings handler without a
 * server-issued school session. Any schoolId in a query, path, or JSON body
 * must match the school embedded in that signed session.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SchoolAuthFilter extends OncePerRequestFilter {
    private static final long MAX_INSPECTED_BODY_BYTES = 16L * 1024L * 1024L;
    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            "/api/students", "/api/staff", "/api/finance", "/api/attendance",
            "/api/biometric", "/api/settings");

    @Autowired
    private SchoolSessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isProtectedPath(request.getRequestURI()) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : null;
        SchoolSessionService.Principal principal = sessionService.verifyToken(token);
        if (principal == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or expired school session. Please log in again.");
            return;
        }

        String schoolId = principal.getSchoolId();
        String requestedSchoolId = request.getParameter("schoolId");
        if (requestedSchoolId != null && !schoolId.equals(requestedSchoolId.trim())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "The requested school does not match the authenticated school.");
            return;
        }

        if (isSettingsPath(request.getRequestURI())) {
            String pathSchoolId = settingsPathSchoolId(request.getRequestURI());
            if (pathSchoolId != null && !schoolId.equals(pathSchoolId)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "The requested school does not match the authenticated school.");
                return;
            }
        }

        HttpServletRequest downstreamRequest = request;
        if (hasJsonBody(request)) {
            if (request.getContentLengthLong() > MAX_INSPECTED_BODY_BYTES) {
                writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "Request body is too large.");
                return;
            }
            CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
            if (!bodySchoolIdsMatch(cached.getCachedBody(), schoolId)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "The request body does not match the authenticated school.");
                return;
            }
            downstreamRequest = cached;
        }

        downstreamRequest.setAttribute(SchoolSessionService.SCHOOL_ID_ATTRIBUTE, schoolId);
        filterChain.doFilter(downstreamRequest, response);
    }

    private boolean isProtectedPath(String uri) {
        if (uri == null) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private boolean isSettingsPath(String uri) {
        return uri != null && (uri.equals("/api/settings") || uri.startsWith("/api/settings/"));
    }

    private String settingsPathSchoolId(String uri) {
        if (!isSettingsPath(uri)) return null;
        String remainder = uri.substring("/api/settings/".length());
        int slash = remainder.indexOf('/');
        return slash >= 0 ? remainder.substring(0, slash) : remainder;
    }

    private boolean hasJsonBody(HttpServletRequest request) {
        String method = request.getMethod();
        String contentType = request.getContentType();
        return ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method))
                && contentType != null && contentType.toLowerCase().contains("application/json");
    }

    private boolean bodySchoolIdsMatch(byte[] body, String authenticatedSchoolId) {
        if (body == null || body.length == 0) return true;
        try {
            JsonNode root = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            return bodySchoolIdsMatch(root, authenticatedSchoolId);
        } catch (IOException ex) {
            // Let Spring's normal JSON validation produce the client error.
            return true;
        }
    }

    private boolean bodySchoolIdsMatch(JsonNode node, String authenticatedSchoolId) {
        if (node == null) return true;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("schoolId".equalsIgnoreCase(field.getKey()) && !field.getValue().isNull()) {
                    if (!field.getValue().isTextual()
                            || !authenticatedSchoolId.equals(field.getValue().asText().trim())) return false;
                }
                if (!bodySchoolIdsMatch(field.getValue(), authenticatedSchoolId)) return false;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (!bodySchoolIdsMatch(child, authenticatedSchoolId)) return false;
            }
        }
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
