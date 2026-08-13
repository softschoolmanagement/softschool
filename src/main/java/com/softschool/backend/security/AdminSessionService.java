package com.softschool.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Issues and verifies short-lived signed session tokens for the Super
 * Admin panel (superadmin.html / superadmin.js).
 *
 * ── The actual secret never reaches the browser ────────────────────────
 * The super admin panel is a static page fetched directly by the browser,
 * so it can NEVER safely hold a long-lived static API key — anything baked
 * into that JS is visible to anyone who opens dev tools or views source.
 * Instead:
 *   1. The browser POSTs a username/password once to /api/admin-auth/login.
 *   2. The server checks it against SUPERADMIN_USERNAME /
 *      SUPERADMIN_PASSWORD_HASH (env vars — see .env.example) and, if
 *      correct, mints a signed token: base64(payload) + "." + signature.
 *   3. The browser stores ONLY that token (in sessionStorage, so it's
 *      cleared when the tab closes) and sends it as
 *      "Authorization: Bearer <token>" on every /api/admin/** call.
 *   4. AdminAuthFilter verifies the signature server-side on every
 *      request. The signing secret (ADMIN_SESSION_SECRET) itself is only
 *      ever read from the environment and is never sent to, or derivable
 *      by, the client.
 * A stolen token is far less damaging than a stolen static key: it expires
 * quickly (default 60 min) and can't be used to derive the signing secret.
 *
 * ── Key rotation ────────────────────────────────────────────────────────
 * To rotate ADMIN_SESSION_SECRET with zero downtime:
 *   1. Set ADMIN_SESSION_SECRET_PREVIOUS to the current (old) secret value.
 *   2. Set ADMIN_SESSION_SECRET to a new random value and redeploy.
 *   New tokens are signed with the new secret; tokens already signed with
 *   the old secret still verify (against SECRET_PREVIOUS) until they
 *   expire naturally. Once you're sure no old tokens are in use, remove
 *   ADMIN_SESSION_SECRET_PREVIOUS.
 */
@Service
public class AdminSessionService {

    @Value("${admin.session-secret:}")
    private String currentSecret;

    @Value("${admin.session-secret-previous:}")
    private String previousSecret;

    @Value("${admin.session-ttl-minutes:60}")
    private long ttlMinutes;

    private static final String ALGO = "HmacSHA256";

    public boolean isConfigured() {
        return currentSecret != null && !currentSecret.isBlank();
    }

    /** Mints "<base64 payload>.<base64 signature>" for the given admin username. */
    public String issueToken(String username) {
        if (!isConfigured()) {
            throw new IllegalStateException("ADMIN_SESSION_SECRET is not configured.");
        }
        long expiresAtEpochMs = System.currentTimeMillis() + (ttlMinutes * 60_000L);
        String payload = username + "|" + expiresAtEpochMs;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(payloadB64, currentSecret);
        return payloadB64 + "." + signature;
    }

    /** Returns the admin username if the token is validly signed and not expired, otherwise null. */
    public String verifyToken(String token) {
        if (token == null || token.isBlank() || !isConfigured()) return null;
        int dot = token.lastIndexOf('.');
        if (dot < 0) return null;

        String payloadB64 = token.substring(0, dot);
        String signature = token.substring(dot + 1);

        boolean validNow = MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                sign(payloadB64, currentSecret).getBytes(StandardCharsets.UTF_8));

        boolean validPrevious = previousSecret != null && !previousSecret.isBlank()
                && MessageDigest.isEqual(
                        signature.getBytes(StandardCharsets.UTF_8),
                        sign(payloadB64, previousSecret).getBytes(StandardCharsets.UTF_8));

        if (!validNow && !validPrevious) return null;

        try {
            String payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", 2);
            if (parts.length != 2) return null;
            long expiresAtEpochMs = Long.parseLong(parts[1]);
            if (System.currentTimeMillis() > expiresAtEpochMs) return null; // expired
            return parts[0];
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
