package com.softschool.backend.controller;

import com.softschool.backend.security.AdminSessionService;
import com.softschool.backend.security.PasswordHashUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/**
 * Login for the Super Admin panel (superadmin.html). Replaces the previous
 * "no login here on purpose" approach, which relied entirely on the panel's
 * URL being unpublicized — not a real protection, especially in a public
 * repo. Credentials are never hardcoded: both are supplied via environment
 * variables (see .env.example) and the password is compared as a salted
 * hash, never in plaintext. On success this issues a short-lived signed
 * session token (see AdminSessionService) — no static key is ever sent to
 * or stored by the browser.
 */
@RestController
@RequestMapping("/api/admin-auth")
public class AdminAuthController {

    @Autowired
    private AdminSessionService sessionService;

    @Value("${admin.superadmin-username:}")
    private String configuredUsername;

    @Value("${admin.superadmin-password-hash:}")
    private String configuredPasswordHash;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginBody body) {
        if (!sessionService.isConfigured()
                || configuredUsername == null || configuredUsername.isBlank()
                || configuredPasswordHash == null || configuredPasswordHash.isBlank()) {
            // Fails closed: if the deployment hasn't set ADMIN_SESSION_SECRET /
            // SUPERADMIN_USERNAME / SUPERADMIN_PASSWORD_HASH yet, the admin
            // panel is simply unreachable rather than falling back to any
            // default/hardcoded credential.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("Admin login is not configured on this server."));
        }

        String username = body == null ? null : body.getUsername();
        String password = body == null ? null : body.getPassword();

        if (username == null || password == null
                || !constantTimeUsernameEquals(username.trim(), configuredUsername.trim())
                || !PasswordHashUtil.verify(password, configuredPasswordHash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("Invalid username or password."));
        }

        String token = sessionService.issueToken(username.trim());
        return ResponseEntity.ok(Collections.singletonMap("token", token));
    }

    // Plain equals() is fine for a username (not a secret), but keeping the
    // comparison style consistent/explicit here for clarity.
    private boolean constantTimeUsernameEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    private Map<String, String> error(String message) {
        return Collections.singletonMap("error", message);
    }

    public static class LoginBody {
        private String username;
        private String password;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
