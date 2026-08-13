package com.softschool.backend.controller;

import com.softschool.backend.model.School;
import com.softschool.backend.repository.SchoolRepository;
import com.softschool.backend.service.LoginAttemptService;
import com.softschool.backend.security.SchoolSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
// import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public-facing auth endpoints used by the school login page (index.html).
 *
 * Kept separate from SuperAdminController / "/api/admin/**" on purpose:
 * this is reachable by anyone with a School ID + security code, while
 * /api/admin/** is only ever called from the (unlinked, URL-hidden) super
 * admin portal.
 *
 * Flow:
 *  1. Super admin creates a school in the super admin portal -> the school
 *     gets a School ID + a permanent 7-character security code
 *     (School.schoolId / School.password).
 *  2. The school registers ONCE here with that School ID + security code,
 *     and picks its own username + password
 *     (School.username / School.loginPasswordHash).
 *  3. From then on, the school logs in from anywhere with username + password.
 */
@RestController
@RequestMapping("/api/school")
@CrossOrigin(origins = "*")
public class SchoolAuthController {

    @Autowired
    private SchoolRepository schoolRepository;

    @Autowired
    private SchoolSessionService schoolSessionService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REMEMBER_TOKEN_DAYS = 30;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        String schoolId = trim(req.getSchoolId());
        String code = trim(req.getCode());
        String username = trim(req.getUsername());
        String password = req.getPassword();

        if (schoolId == null || schoolId.isEmpty()) {
            return badRequest("Please enter the School ID given to you.");
        }
        if (code == null || code.isEmpty()) {
            return badRequest("Please enter your 7-character security code.");
        }
        if (username == null || username.length() < 4) {
            return badRequest("Username must be at least 4 characters.");
        }
        if (username.contains(" ")) {
            return badRequest("Username cannot contain spaces.");
        }
        if (password == null || password.length() < 6) {
            return badRequest("Password must be at least 6 characters.");
        }

        Optional<School> found = schoolRepository.findBySchoolId(schoolId);
        if (!found.isPresent()) {
            return badRequest("School ID or security code is not valid.");
        }
        School school = found.get();

        if (school.getPassword() == null || !school.getPassword().equalsIgnoreCase(code)) {
            return badRequest("School ID or security code is not valid.");
        }
        if (school.getUsername() != null && !school.getUsername().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("This School ID is already registered. Please login instead."));
        }
        if (schoolRepository.existsByUsernameIgnoreCase(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("That username is already taken. Try another one."));
        }

        school.setUsername(username);
        school.setLoginPasswordHash(hashPassword(password));
        School saved = schoolRepository.save(school);

        return ResponseEntity.status(HttpStatus.CREATED).body(new SchoolPublicView(saved));
    }

    /**
     * "Forgot password" flow for schools that already registered.
     * Re-proves ownership the same way /register does — School ID +
     * the permanent security code from the super admin — then, instead of
     * creating a NEW username, requires the school's EXISTING username to
     * be typed in (so a leaked/guessed schoolId+code pair alone can't be
     * used to take over an already-registered school's account) and
     * simply overwrites loginPasswordHash. Nothing else about the school
     * (username, remember-me token, etc.) changes.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest req) {
        String schoolId = trim(req.getSchoolId());
        String code = trim(req.getCode());
        String username = trim(req.getUsername());
        String newPassword = req.getNewPassword();

        if (schoolId == null || schoolId.isEmpty()) {
            return badRequest("Please enter the School ID given to you.");
        }
        if (code == null || code.isEmpty()) {
            return badRequest("Please enter your 7-character security code.");
        }
        if (username == null || username.isEmpty()) {
            return badRequest("Please enter your username.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            return badRequest("New password must be at least 6 characters.");
        }

        Optional<School> found = schoolRepository.findBySchoolId(schoolId);
        if (!found.isPresent()) {
            return badRequest("School ID or security code is not valid.");
        }
        School school = found.get();

        if (school.getPassword() == null || !school.getPassword().equalsIgnoreCase(code)) {
            return badRequest("School ID or security code is not valid.");
        }
        if (school.getUsername() == null || school.getUsername().isEmpty()) {
            return badRequest("This School ID has not registered a username yet. Please register first.");
        }
        if (!school.getUsername().equalsIgnoreCase(username)) {
            return badRequest("That username does not match this School ID.");
        }

        school.setLoginPasswordHash(hashPassword(newPassword));
        // A password reset invalidates any outstanding "Remember Me" token
        // for this school, on every browser, so a lost/leaked token can't
        // keep working after the password has been changed.
        school.setRememberTokenHash(null);
        school.setRememberTokenExpiry(null);
        schoolRepository.save(school);

        return ResponseEntity.ok(Collections.singletonMap("message", "Password reset successful. Please login with your new password."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        String username = trim(req.getUsername());
        String password = req.getPassword();

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return badRequest("Please enter your username and password.");
        }

        // Checked BEFORE touching credentials at all: while locked out, not
        // even the correct password gets through, and we don't want a
        // successful guess to reset a lockout that's still in effect.
        LoginAttemptService.LockStatus lockStatus = loginAttemptService.checkLocked(username);
        if (lockStatus.locked) {
            return tooManyAttempts(lockStatus.retryAfterSeconds);
        }

        Optional<School> found = schoolRepository.findByUsernameIgnoreCase(username);
        if (!found.isPresent() || found.get().getLoginPasswordHash() == null
                || !verifyPassword(password, found.get().getLoginPasswordHash())) {
            return handleFailedLogin(username);
        }

        School school = found.get();
        if ("blocked".equalsIgnoreCase(school.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("blocked"));
        }
        if (school.getExpiryDate() != null && school.getExpiryDate().isBefore(LocalDate.now())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("expired"));
        }

        // Correct credentials, account in good standing - clear any failure history.
        loginAttemptService.recordSuccess(username);

        String rememberToken = null;
        if (req.isRemember()) {
            rememberToken = issueRememberToken(school);
        }
        School saved = schoolRepository.save(school);

        SchoolPublicView view = new SchoolPublicView(saved);
        view.rememberToken = rememberToken; // null unless "Remember Me" was checked
        view.sessionToken = schoolSessionService.issueToken(saved.getSchoolId(), saved.getUsername());
        return ResponseEntity.ok(view);
    }

    /**
     * FEATURE — login attempt warnings + account lockout:
     *  - Attempts 1-2: plain invalid-credentials message.
     *  - Attempts 3-4: same, plus "you have made N attempts, M left".
     *  - Attempt 5: locks the account for LoginAttemptService.LOCKOUT_MINUTES
     *    and returns 429 instead of 401 so the frontend can tell the two
     *    cases apart.
     */
    private ResponseEntity<?> handleFailedLogin(String username) {
        LoginAttemptService.FailureResult result = loginAttemptService.recordFailure(username);
        if (result.justLocked) {
            return tooManyAttempts(result.retryAfterSeconds);
        }

        String message = "Invalid username or password.";
        if (result.attemptCount >= 3) {
            message += " You have made " + result.attemptCount + " login attempt"
                    + (result.attemptCount == 1 ? "" : "s") + ". "
                    + result.attemptsLeft + " attempt" + (result.attemptsLeft == 1 ? "" : "s")
                    + " left before your account is locked.";
        }

        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        body.put("attemptCount", result.attemptCount);
        body.put("attemptsLeft", result.attemptsLeft);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private ResponseEntity<?> tooManyAttempts(long retryAfterSeconds) {
        long minutesLeft = Math.max(1, (retryAfterSeconds + 59) / 60);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "You have reached the maximum login attempts (" + LoginAttemptService.MAX_ATTEMPTS
                + "). Try again after " + minutesLeft + " minute" + (minutesLeft == 1 ? "" : "s") + ".");
        body.put("locked", true);
        body.put("retryAfterSeconds", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /**
     * Silent auto-login used by index.html on page load: trades a
     * previously-issued "Remember Me" token for a fresh session, without
     * the user typing a username/password again. The token is rotated
     * (a new one issued and returned) on every successful use.
     */
    @PostMapping("/login-token")
    public ResponseEntity<?> loginWithToken(@RequestBody TokenLoginRequest req) {
        String username = trim(req.getUsername());
        String token = req.getToken();

        if (username == null || username.isEmpty() || token == null || token.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("Invalid session."));
        }

        Optional<School> found = schoolRepository.findByUsernameIgnoreCase(username);
        if (!found.isPresent()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("Invalid session."));
        }

        School school = found.get();
        boolean tokenOk = school.getRememberTokenHash() != null
                && school.getRememberTokenExpiry() != null
                && school.getRememberTokenExpiry().isAfter(Instant.now())
                && verifyPassword(token, school.getRememberTokenHash());

        if (!tokenOk) {
            // Whatever is stored (expired/mismatched/reused) is no longer
            // good for anything — clear it so it can't be retried.
            school.setRememberTokenHash(null);
            school.setRememberTokenExpiry(null);
            schoolRepository.save(school);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("Invalid session."));
        }
        if ("blocked".equalsIgnoreCase(school.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("blocked"));
        }
        if (school.getExpiryDate() != null && school.getExpiryDate().isBefore(LocalDate.now())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("expired"));
        }

        String newToken = issueRememberToken(school);
        School saved = schoolRepository.save(school);

        SchoolPublicView view = new SchoolPublicView(saved);
        view.rememberToken = newToken;
        view.sessionToken = schoolSessionService.issueToken(saved.getSchoolId(), saved.getUsername());
        return ResponseEntity.ok(view);
    }

    /** Called when the school explicitly logs out, so the "Remember Me" token stops working on this (and every) browser. */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody LogoutRequest req) {
        String username = trim(req.getUsername());
        if (username != null && !username.isEmpty()) {
            schoolRepository.findByUsernameIgnoreCase(username).ifPresent(school -> {
                school.setRememberTokenHash(null);
                school.setRememberTokenExpiry(null);
                schoolRepository.save(school);
            });
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam String username) {
        String uname = trim(username);
        if (uname == null || uname.isEmpty()) {
            return badRequest("Username is required.");
        }
        Optional<School> found = schoolRepository.findByUsernameIgnoreCase(uname);
        if (!found.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("not_found"));
        }
        return ResponseEntity.ok(new SchoolPublicView(found.get()));
    }

    // ── helpers ──────────────────────────────────────────────

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        return Collections.singletonMap("error", message);
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = sha256(salt, password);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = sha256(salt, password);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] sha256(byte[] salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(password.getBytes("UTF-8"));
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Mints a new random "Remember Me" token for a school, stores its
     * salted hash + a {@value #REMEMBER_TOKEN_DAYS}-day expiry on the
     * entity (caller is responsible for saving it), and returns the PLAIN
     * token so it can be sent to the browser this one time only.
     */
    private String issueRememberToken(School school) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        school.setRememberTokenHash(hashPassword(token));
        school.setRememberTokenExpiry(Instant.now().plus(REMEMBER_TOKEN_DAYS, ChronoUnit.DAYS));
        return token;
    }

    // ── request/response classes ────────────────────────────

    public static class RegisterRequest {
        private String schoolId;
        private String code;
        private String username;
        private String password;
        public String getSchoolId() { return schoolId; }
        public void setSchoolId(String schoolId) { this.schoolId = schoolId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class ResetPasswordRequest {
        private String schoolId;
        private String code;
        private String username;
        private String newPassword;
        public String getSchoolId() { return schoolId; }
        public void setSchoolId(String schoolId) { this.schoolId = schoolId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }

    public static class LoginRequest {
        private String username;
        private String password;
        private boolean remember;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isRemember() { return remember; }
        public void setRemember(boolean remember) { this.remember = remember; }
    }

    public static class TokenLoginRequest {
        private String username;
        private String token;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class LogoutRequest {
        private String username;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
    }

    /** Never expose the security code or the login password hash to the browser. */
    public static class SchoolPublicView {
        public Long id;
        public String schoolId;
        public String name;
        public String username;
        public String prefix;
        public String logo;
        public String planId;
        public Integer studentLimit;
        public Integer staffLimit;
        public Integer archiveStudentLimit;
        public String locks;
        public String status;
        public String registeredAt;
        public String expiryDate;

        /**
         * Only ever non-null in the single response right after a new
         * "Remember Me" token is issued (login or login-token). Never
         * read back from storage — the plain token isn't stored anywhere.
         */
        public String rememberToken;
        public String sessionToken;

        public SchoolPublicView(School s) {
            this.id = s.getId();
            this.schoolId = s.getSchoolId();
            this.name = s.getName();
            this.username = s.getUsername();
            this.prefix = s.getPrefix();
            this.logo = s.getLogo();
            this.planId = s.getPlanId();
            this.studentLimit = s.getStudentLimit();
            this.staffLimit = s.getStaffLimit();
            // this.archiveStudentLimit = s.getArchiveStudentLimit();
            this.locks = s.getLocks();
            this.status = s.getStatus();
            this.registeredAt = s.getRegisteredAt() != null ? s.getRegisteredAt().toString() : null;
            this.expiryDate = s.getExpiryDate() != null ? s.getExpiryDate().toString() : null;
        }
    }
}