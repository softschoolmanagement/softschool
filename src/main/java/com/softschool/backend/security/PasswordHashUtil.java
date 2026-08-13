package com.softschool.backend.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared salted-hash helper (SHA-256 + 16-byte random salt), extracted from
 * the scheme SchoolAuthController already used for school logins, so every
 * place in the app that needs to store/verify a secret does it the same
 * secure way instead of inventing a new (or hardcoded) one.
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
 * A hash produced by hash(...) looks like: "<base64 salt>:<base64 hash>"
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashUtil() {}

    public static String hash(String plaintext) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] digest = sha256(salt, plaintext);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(digest);
    }

    /** Constant-time compare against a "salt:hash" string produced by hash(). */
    public static boolean verify(String plaintext, String stored) {
        if (plaintext == null || stored == null) return false;
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = sha256(salt, plaintext);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] salt, String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(plaintext.getBytes("UTF-8"));
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
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
