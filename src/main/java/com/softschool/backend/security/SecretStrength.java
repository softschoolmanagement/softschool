package com.softschool.backend.security;

import java.util.Locale;
import java.util.Set;

/**
 * Minimum quality bar for HMAC signing secrets (session tokens, etc).
 * A weak secret lets an attacker forge tokens offline, so a secret that is
 * too short or an obvious placeholder is treated as "not configured" and the
 * feature fails closed (401/503) instead of running with a guessable key.
 * Generate a good one with:  openssl rand -base64 48
 */
public final class SecretStrength {

    public static final int MIN_LENGTH = 32;

    private static final Set<String> PLACEHOLDERS = Set.of(
            "changeme", "change-me", "secret", "password", "your-secret-here",
            "replace-me", "replace_me", "example", "test", "default");

    private SecretStrength() {}

    public static boolean isStrong(String secret) {
        if (secret == null) return false;
        String s = secret.trim();
        if (s.length() < MIN_LENGTH) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        for (String p : PLACEHOLDERS) {
            if (lower.contains(p)) return false;
        }
        // Reject a single repeated character ("aaaa...").
        return s.chars().distinct().count() >= 8;
    }
}
