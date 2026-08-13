package com.softschool.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Issues and verifies short-lived signed sessions for a registered school.
 * The signing secret is server-only and must be supplied through
 * SCHOOL_SESSION_SECRET. Tokens contain the school identity, not a client-
 * supplied schoolId, so protected controllers can safely scope every request.
 */
@Service
public class SchoolSessionService {
    public static final String SCHOOL_ID_ATTRIBUTE = SchoolSessionService.class.getName() + ".schoolId";
    private static final String ALGORITHM = "HmacSHA256";

    @Value("${school.session-secret:}")
    private String currentSecret;

    @Value("${school.session-secret-previous:}")
    private String previousSecret;

    @Value("${school.session-ttl-minutes:1000}")
    private long ttlMinutes;

    public boolean isConfigured() {
        return currentSecret != null && !currentSecret.isBlank();
    }

    /** Mints a token containing the authenticated school and username. */
    public String issueToken(String schoolId, String username) {
        if (!isConfigured()) {
            throw new IllegalStateException("SCHOOL_SESSION_SECRET is not configured.");
        }
        long expiresAt = System.currentTimeMillis() + (ttlMinutes * 60_000L);
        String payload = encode(schoolId) + "." + encode(username) + "." + expiresAt;
        String payloadB64 = encode(payload);
        return payloadB64 + "." + sign(payloadB64, currentSecret);
    }

    /** Returns the school principal when the token is valid, otherwise null. */
    public Principal verifyToken(String token) {
        if (token == null || token.isBlank() || !isConfigured()) return null;

        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return null;
        String payloadB64 = token.substring(0, dot);
        String signature = token.substring(dot + 1);

        boolean valid = matches(signature, payloadB64, currentSecret)
                || (previousSecret != null && !previousSecret.isBlank()
                    && matches(signature, payloadB64, previousSecret));
        if (!valid) return null;

        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8)
                    .split("\\.", 3);
            if (parts.length != 3) return null;
            long expiresAt = Long.parseLong(parts[2]);
            if (expiresAt <= System.currentTimeMillis()) return null;
            String schoolId = decode(parts[0]);
            String username = decode(parts[1]);
            if (schoolId.isBlank() || username.isBlank()) return null;
            return new Principal(schoolId, username, expiresAt);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean matches(String supplied, String payload, String secret) {
        if (secret == null || secret.isBlank()) return false;
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                sign(payload, secret).getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String value, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign school session.", ex);
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public static final class Principal {
        private final String schoolId;
        private final String username;
        private final long expiresAt;

        public Principal(String schoolId, String username, long expiresAt) {
            this.schoolId = schoolId;
            this.username = username;
            this.expiresAt = expiresAt;
        }

        public String getSchoolId() { return schoolId; }
        public String getUsername() { return username; }
        public long getExpiresAt() { return expiresAt; }
    }
}
