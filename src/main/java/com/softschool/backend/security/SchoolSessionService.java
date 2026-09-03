package com.softschool.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Issues and verifies signed sessions for a registered school.
 * The signing secret is server-only and must be supplied through
 * SCHOOL_SESSION_SECRET. Tokens contain the school identity, not a client-
 * supplied schoolId, so protected controllers can safely scope every request.
 *
 * NOTE — no session limit: school sessions used to expire after
 * school.session-ttl-minutes (default 60) and silently log the school out,
 * forcing a re-login. That auto-expiry has been removed on request — a
 * school session now stays valid until the school explicitly logs out (or
 * an admin rotates SCHOOL_SESSION_SECRET). The token still carries an
 * "expiresAt" field for backward compatibility with the existing
 * "schoolId.username.expiresAt" payload shape, but it's now set far in the
 * future and is no longer checked in verifyToken().
 */
@Service
public class SchoolSessionService {
    public static final String SCHOOL_ID_ATTRIBUTE = SchoolSessionService.class.getName() + ".schoolId";
    private static final String ALGORITHM = "HmacSHA256";

    // Effectively "never expires" — used to fill the legacy expiresAt slot
    // in the token payload without actually limiting the session.
    private static final long NO_EXPIRY = Long.MAX_VALUE;

    @Value("${school.session-secret:}")
    private String currentSecret;

    @Value("${school.session-secret-previous:}")
    private String previousSecret;

    public boolean isConfigured() {
        return currentSecret != null && !currentSecret.isBlank();
    }

    /** Mints a token containing the authenticated school and username. */
    public String issueToken(String schoolId, String username) {
        if (!isConfigured()) {
            throw new IllegalStateException("SCHOOL_SESSION_SECRET is not configured.");
        }
        String payload = encode(schoolId) + "." + encode(username) + "." + NO_EXPIRY;
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
            // Session limit removed: expiresAt is still parsed (older
            // tokens minted before this change carry a real timestamp
            // here) but is intentionally no longer checked against the
            // current time, so a school is never auto-logged-out.
            long expiresAt = Long.parseLong(parts[2]);
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
