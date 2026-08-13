package com.softschool.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "schools")
@Data               // Automatically creates Getters, Setters, toString, equals, hashCode
@NoArgsConstructor  // Required by JPA
@AllArgsConstructor // For convenience
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Public-facing ID: SS_77_1, SS_77_2, etc.
    @Column(name = "school_id", unique = true, nullable = false, updatable = false)
    private String schoolId;

    private String name;

    // 2-4 letter prefix (e.g. HRK)
    private String prefix;

    // Stored as plain text security code. This is a PERMANENT code (not
    // one-time) — it's intentionally included in GET responses so the
    // super admin can view it any time from the school's details page.
    // NOTE: schools no longer have a username — they log in with their
    // School ID (schoolId, above) + this security code.
    private String password;

    // ── SCHOOL SELF-SERVICE LOGIN (public login page) ──
    // A school registers ONCE (from index.html) using its School ID + the
    // permanent security code above, then picks its own username + password.
    // From then on it logs in anywhere with just username + password,
    // without needing the School ID or security code again. These are kept
    // separate from schoolId/password on purpose: schoolId + code is the
    // "activation key" issued by the super admin; username/loginPasswordHash
    // is what the school itself controls. Both are null until the school
    // completes registration.
    @Column(unique = true)
    private String username;

    // Hashed + salted login password chosen at registration time
    // (format: "base64(salt):base64(sha256Hash)"). The plain password is
    // never stored.
    @Column(name = "login_password_hash")
    private String loginPasswordHash;

    // ── "REMEMBER ME" (persistent login) ──
    // When a school checks "Remember Me" at login, the backend mints a
    // long, random one-time token, hands the PLAIN token to the browser
    // exactly once, and stores only its salted hash here (same
    // "base64(salt):base64(sha256Hash)" format as loginPasswordHash — the
    // plain token itself is never persisted). The browser keeps the plain
    // token in localStorage and trades it in on every future visit via
    // /api/school/login-token to get logged straight into main.html
    // without re-entering a username/password.
    //
    // The token is rotated (a new one issued) every time it's used, so a
    // copy that leaks from an old backup/log becomes worthless once the
    // real browser uses it again, and rememberTokenExpiry gives it a hard
    // 30-day lifetime either way.
    @Column(name = "remember_token_hash")
    private String rememberTokenHash;

    @Column(name = "remember_token_expiry")
    private Instant rememberTokenExpiry;

    // Use @Lob and LONGTEXT for MySQL to store large Base64 image strings
    @Lob
    @Column(name = "logo", columnDefinition = "LONGTEXT")
    private String logo;

    private String planId;

    private Integer studentLimit;

    // Max number of staff members (teaching + non-teaching) this school can have.
    // Falls back to the plan's staffLimit when not customized.
    private Integer staffLimit;

    // Feature keys locked for this school, e.g. "biometric,finance"
    // Use TEXT for longer lists of locks
    @Column(name = "locks", columnDefinition = "TEXT")
    private String locks;

    private LocalDate registeredAt;
    
    private LocalDate expiryDate;

    private String status; // "active" | "blocked"
}