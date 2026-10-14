package com.softschool.backend.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared password-hash helper. New hashes use PBKDF2-HMAC-SHA256 with a
 * random 16-byte salt and 600,000 iterations (OWASP 2023 minimum), which is
 * deliberately slow so a stolen database cannot be brute-forced quickly.
 * Legacy single-round "salt:hash" SHA-256 values are still ACCEPTED by
 * verify() so existing accounts keep working, and needsRehash() tells callers
 * to upgrade them on the next successful login.
 *
 * ── Why this exists ─────────────────────────────────────────────────────
 * A previous version of this codebase had a live, unauthenticated backdoor:
 * AuthController.login() compared the incoming request against a literal
 * hardcoded phone number + password baked into the source file. Anyone who
 * read the (public) repo had full valid credentials. This util replaces
 * that pattern everywhere: nothing in this codebase should ever compare a
 * request against a plaintext literal again. Secrets are supplied only via
 * environment variables (see .env.example), and only ever stored/compared
 * as a salted hash.
 *
 * ── Format ──────────────────────────────────────────────────────────────
 * A hash produced by hash(...) looks like: "pbkdf2$<iterations>$<base64 salt>$<base64 hash>"
 * (legacy format, verify-only: "<base64 salt>:<base64 sha256>")
 * That whole string is what you put in an environment variable such as
 * SUPERADMIN_PASSWORD_HASH — never the plaintext password itself.
 *
 * ── Generating a hash for an env var ───────────────────────────────────
 * Run this file's main() locally (never on a server, never commit the
 * output anywhere but your secrets manager / .env):
 *   javac PasswordHashUtil.java && java com.softschool.backend.security.PasswordHashUtil "your-password"
 * Copy the printed value into SUPERADMIN_PASSWORD_HASH (or similar) in
 * your environment/secrets manager. The plaintext password is never
 * stored anywhere after this.
 */
public final class PasswordHashUtil {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    /** Upper bound so a hostile stored value can't make verify() burn CPU. */
    private static final int MAX_ITERATIONS = 5_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashUtil() {}

    public static String hash(String plaintext) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(plaintext, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    /** Constant-time compare against a stored hash (PBKDF2 or legacy SHA-256). */
    public static boolean verify(String plaintext, String stored) {
        if (plaintext == null || stored == null) return false;
        try {
            if (stored.startsWith(PREFIX + "$")) {
                String[] p = stored.split("\\$");
                if (p.length != 4) return false;
                int iterations = Integer.parseInt(p[1]);
                if (iterations < 1 || iterations > MAX_ITERATIONS) return false;
                byte[] salt = Base64.getDecoder().decode(p[2]);
                byte[] expected = Base64.getDecoder().decode(p[3]);
                return MessageDigest.isEqual(expected, pbkdf2(plaintext, salt, iterations));
            }
            // Legacy format: "<base64 salt>:<base64 sha256(salt + password)>"
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expected, legacySha256(salt, plaintext));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the stored value is in the old weak format (or has fewer iterations than today's default). */
    public static boolean needsRehash(String stored) {
        if (stored == null || stored.isBlank()) return false;
        if (!stored.startsWith(PREFIX + "$")) return true;
        try {
            return Integer.parseInt(stored.split("\\$")[1]) < ITERATIONS;
        } catch (Exception e) {
            return true;
        }
    }

    private static byte[] pbkdf2(String plaintext, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plaintext.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static byte[] legacySha256(byte[] salt, String plaintext) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        return digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /** Offline CLI helper — never wire this up as a live HTTP endpoint. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java com.softschool.backend.security.PasswordHashUtil \"<password>\"");
            return;
        }
        System.out.println(hash(args[0]));
    }
}
