package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.dto.ParentSignupDTO;
import com.softschool.backend.model.ParentLinkedChild;
import com.softschool.backend.repository.ParentChildRepository;
import com.softschool.backend.repository.ParentLinkedChildRepository;
import org.springframework.dao.DataIntegrityViolationException;
import com.softschool.backend.model.ParentAccount;
import com.softschool.backend.model.School;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.repository.ParentAccountRepository;
import com.softschool.backend.repository.ParentPortalConfigRepository;
import com.softschool.backend.repository.SchoolRepository;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.security.PasswordHashUtil;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.service.LoginAttemptService;
import com.softschool.backend.service.ParentChildrenService;
import com.softschool.backend.service.ParentContext;
import com.softschool.backend.service.ParentUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Parent portal sign-in.
 *
 *   POST /api/parent/login            { phone, password, schoolId? }  -> { token, schoolId, school, children[], passwordChanged }
 *   POST /api/parent/change-password  { phone, oldPassword, newPassword }   (needs the parent token)
 *
 * A parent signs in with the phone number written on their child's student record. Until they change
 * it, the password is the school-wide default the admin sets (PUT /api/parent-admin/default-password).
 * Wrong guesses share the same 5-attempt lockout as school and teacher logins, and an unknown phone
 * and a wrong password give the same 401 so the endpoint cannot be used to find out which numbers exist.
 *
 * schoolId is only needed in the rare case where the same phone number AND password both match
 * children in two different schools; the response is then 409 with the list to choose from.
 */
@RestController
@RequestMapping("/api/parent")
public class ParentAuthController {

    private static final int MIN_NEW_PASSWORD = 7;   // matches the rule on the change-password page

    @Autowired private ParentChildrenService childrenService;
    @Autowired private ParentChildRepository childRepository;
    @Autowired private ParentLinkedChildRepository linkedChildRepository;
    @Autowired private ParentContext parentContext;
    @Autowired private ParentAccountRepository accountRepository;
    @Autowired private ParentPortalConfigRepository configRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolSettingsRepository settingsRepository;
    @Autowired private SchoolSessionService sessionService;
    @Autowired private LoginAttemptService loginAttemptService;

    // ───────────── LOGIN ─────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> req) {
        String tail = ParentUtil.tail10(req == null ? null : req.get("phone"));
        String password = req == null ? null : req.get("password");
        String wantedSchool = req == null || req.get("schoolId") == null ? "" : req.get("schoolId").trim();
        if (tail == null || password == null || password.isEmpty()) {
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "Please enter your registered phone number and password.");
        }

        String lockKey = ParentContext.PREFIX + tail;
        LoginAttemptService.LockStatus lock = loginAttemptService.checkLocked(lockKey);
        if (lock.locked) return tooManyAttempts(lock.retryAfterSeconds);

        Map<String, List<ParentChildDTO>> bySchool = ParentChildrenService.bySchool(childrenService.childrenAcrossSchools(tail));
        List<String> matched = new ArrayList<>();
        for (String schoolId : bySchool.keySet()) {
            if (!wantedSchool.isEmpty() && !wantedSchool.equals(schoolId)) continue;
            if (schoolRepository.findBySchoolId(schoolId).isPresent() && passwordOk(schoolId, tail, password)) matched.add(schoolId);
        }
        if (matched.isEmpty()) return failed(lockKey);

        if (matched.size() > 1) {
            List<Map<String, Object>> choices = new ArrayList<>();
            for (String id : matched) choices.add(schoolBrief(id));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "This number is registered in more than one school. Choose your school.");
            body.put("schools", choices);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        String schoolId = matched.get(0);
        School school = schoolRepository.findBySchoolId(schoolId).orElse(null);
        if (school == null) return failed(lockKey);
        if ("blocked".equalsIgnoreCase(school.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Collections.singletonMap("error", "This school account is blocked."));
        }
        if (school.getExpiryDate() != null && school.getExpiryDate().isBefore(LocalDate.now())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Collections.singletonMap("error", "This school's subscription has expired."));
        }

        loginAttemptService.recordSuccess(lockKey);
        ParentAccount account = accountRepository.findBySchoolIdAndPhone(schoolId, tail).orElse(null);
        if (account != null) {
            account.setLastLoginAt(Instant.now());
            accountRepository.save(account);
        }

        boolean changed = account != null && account.getPasswordHash() != null && !account.getPasswordHash().isEmpty();
        boolean mustChange = parentContext.isPasswordChangeRequired() && !changed;

        // A session that signed in with the default password gets no child data until it has changed it.
        List<Map<String, Object>> kids = new ArrayList<>();
        if (!mustChange) for (ParentChildDTO c : childrenService.childrenOf(schoolId, tail)) kids.add(ParentChildrenService.view(c));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", sessionService.issueToken(schoolId, ParentContext.PREFIX + tail));
        out.put("schoolId", schoolId);
        out.put("passwordChanged", changed);
        out.put("mustChangePassword", mustChange);   // true -> send the parent to the change-password page first
        out.put("school", schoolBrief(schoolId));
        out.put("children", kids);
        return ResponseEntity.ok(out);
    }

    // ───────────── SIGN UP ─────────────
    /**
     * POST /api/parent/signup { fatherCnic, phone, regNo, password }
     *
     * A parent creates their own account. Identity is proven by THREE things that must all match ONE
     * active student in the school's records: the student's registration number, the father's CNIC, and
     * a phone number written on that student (phone1 or phone2). The password is exactly 7 digits.
     * Nothing says which of the three was wrong.
     *
     * On success the account is created and every sibling the school's records link to that student
     * (Student.siblingGroupId, and the hasSiblings list) is attached automatically, together with any
     * other child that shares the phone number. The parent then signs in with phone + password.
     *
     * Guessing is limited per phone AND per registration number with the same 5-attempt lockout as login.
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody Map<String, String> req) {
        String cnic = ParentUtil.digitsOnly(req == null ? null : req.get("fatherCnic"));
        String tail = ParentUtil.tail10(req == null ? null : req.get("phone"));
        String regNo = ParentUtil.clip(req == null ? null : req.get("regNo"), 64);
        String password = req == null ? null : req.get("password");

        if (cnic.length() != 13) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Enter the father's 13-digit CNIC.");
        if (tail == null) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Enter a valid phone number.");
        if (regNo.isEmpty()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Enter the student's registration number.");
        if (password == null || !password.matches("\\d{7}")) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Password must be exactly 7 digits.");

        String phoneKey = "parentsignup:" + tail;
        String regKey = "parentsignup:reg:" + regNo.toLowerCase();
        LoginAttemptService.LockStatus l1 = loginAttemptService.checkLocked(phoneKey);
        if (l1.locked) return tooManyAttempts(l1.retryAfterSeconds);
        LoginAttemptService.LockStatus l2 = loginAttemptService.checkLocked(regKey);
        if (l2.locked) return tooManyAttempts(l2.retryAfterSeconds);

        List<ParentSignupDTO> matches = new ArrayList<>();
        for (ParentSignupDTO c : childRepository.findActiveSignupByRegNo(regNo)) {
            boolean phoneOk = ParentUtil.phoneMatches(c.getPhone1(), tail) || ParentUtil.phoneMatches(c.getPhone2(), tail);
            String stored = ParentUtil.digitsOnly(c.getFatherCnic());
            if (stored.isEmpty() && "father".equalsIgnoreCase(c.getGuardianRole() == null ? "" : c.getGuardianRole().trim())) {
                stored = ParentUtil.digitsOnly(c.getGuardianCnic());      // father recorded as the guardian
            }
            boolean cnicOk = stored.length() == 13
                    && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), cnic.getBytes(StandardCharsets.UTF_8));
            if (phoneOk && cnicOk) matches.add(c);
        }
        if (matches.isEmpty()) {
            LoginAttemptService.FailureResult a = loginAttemptService.recordFailure(phoneKey);
            LoginAttemptService.FailureResult b = loginAttemptService.recordFailure(regKey);
            if (a.justLocked || b.justLocked) return tooManyAttempts(Math.max(a.retryAfterSeconds, b.retryAfterSeconds));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "These details do not match the school's records. Check the CNIC, phone number and registration number, or ask the school office to correct them.");
            body.put("attemptsLeft", Math.min(a.attemptsLeft, b.attemptsLeft));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        if (matches.size() > 1) {
            return ParentUtil.error(HttpStatus.CONFLICT, "These details match more than one school. Please contact your school office.");
        }

        ParentSignupDTO student = matches.get(0);
        String schoolId = student.getSchoolId();
        School school = schoolRepository.findBySchoolId(schoolId).orElse(null);
        if (school == null) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Your school is not available right now.");
        if ("blocked".equalsIgnoreCase(school.getStatus())) return ParentUtil.error(HttpStatus.FORBIDDEN, "This school account is blocked.");
        if (school.getExpiryDate() != null && school.getExpiryDate().isBefore(LocalDate.now())) {
            return ParentUtil.error(HttpStatus.FORBIDDEN, "This school's subscription has expired.");
        }

        ParentAccount existing = accountRepository.findBySchoolIdAndPhone(schoolId, tail).orElse(null);
        if (existing != null) {
            return ParentUtil.error(HttpStatus.CONFLICT, "An account already exists for this phone number. Please log in.");
        }

        // Siblings the school's own records link to this student.
        Set<String> wanted = new LinkedHashSet<>();
        wanted.add(student.getRegNo());
        String group = student.getSiblingGroupId() == null ? "" : student.getSiblingGroupId().trim();
        if (!group.isEmpty()) wanted.addAll(childRepository.findActiveRegNosBySiblingGroup(schoolId, group));
        for (String json : childRepository.findHasSiblingsJson(schoolId, student.getRegNo())) {
            try {
                if (json == null || json.isBlank()) continue;
                JsonNode arr = new ObjectMapper().readTree(json);
                if (!arr.isArray()) continue;
                for (JsonNode n : arr) {
                    String r = n.path("regNo").asText("").trim();
                    if (!r.isEmpty()) wanted.add(r);
                }
            } catch (Exception ignored) { /* a malformed list must never block sign-up */ }
        }
        if (wanted.size() > 15) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Too many linked children. Please contact your school office.");

        ParentAccount account = new ParentAccount();
        account.setSchoolId(schoolId);
        account.setPhone(tail);
        account.setPasswordHash(PasswordHashUtil.hash(password));
        account.setPasswordChangedAt(Instant.now());
        try {
            accountRepository.save(account);
        } catch (DataIntegrityViolationException e) {                 // two sign-ups at the same moment
            return ParentUtil.error(HttpStatus.CONFLICT, "An account already exists for this phone number. Please log in.");
        }

        // Only active students of THIS school are attached (re-checked by the query).
        for (ParentChildDTO c : childRepository.findActiveByRegNosInSchool(schoolId, wanted)) {
            if (!linkedChildRepository.existsBySchoolIdAndPhoneAndRegNo(schoolId, tail, c.getRegNo())) {
                ParentLinkedChild link = new ParentLinkedChild();
                link.setSchoolId(schoolId);
                link.setPhone(tail);
                link.setRegNo(c.getRegNo());
                try { linkedChildRepository.save(link); } catch (DataIntegrityViolationException ignored) { /* already linked */ }
            }
        }
        loginAttemptService.recordSuccess(phoneKey);
        loginAttemptService.recordSuccess(regKey);

        List<Map<String, Object>> kids = new ArrayList<>();
        for (ParentChildDTO c : childrenService.childrenOf(schoolId, tail)) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("regNo", c.getRegNo());
            k.put("name", c.getFullName());
            k.put("classLabel", ParentUtil.classLabel(c));
            kids.add(k);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "Account created. You can now log in with your phone number and password.");
        out.put("children", kids);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    // ───────────── CHANGE PASSWORD ─────────────
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> req, HttpServletRequest http) {
        ParentContext.Ctx ctx = parentContext.resolveForPasswordChange(http);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        String oldPassword = req == null ? null : req.get("oldPassword");
        String newPassword = req == null ? null : req.get("newPassword");
        String tail = ParentUtil.tail10(req == null ? null : req.get("phone"));
        // The phone typed on the page must be the one this session belongs to.
        if (tail == null || !tail.equals(ctx.phone)) {
            return ParentUtil.error(HttpStatus.FORBIDDEN, "That phone number does not match your account.");
        }
        if (oldPassword == null || oldPassword.isEmpty()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Enter your current password.");
        if (newPassword == null || newPassword.length() < MIN_NEW_PASSWORD || !newPassword.matches(".*\\d.*")) {
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "New password must be at least " + MIN_NEW_PASSWORD + " characters and include a number.");
        }
        if (newPassword.equals(oldPassword)) return ParentUtil.error(HttpStatus.BAD_REQUEST, "New password must be different from the current one.");
        if (newPassword.length() > 100) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Password is too long.");

        String lockKey = "parentcp:" + ctx.phone;
        LoginAttemptService.LockStatus lock = loginAttemptService.checkLocked(lockKey);
        if (lock.locked) return tooManyAttempts(lock.retryAfterSeconds);

        if (!passwordOk(ctx.schoolId, ctx.phone, oldPassword)) {
            LoginAttemptService.FailureResult r = loginAttemptService.recordFailure(lockKey);
            if (r.justLocked) return tooManyAttempts(r.retryAfterSeconds);
            return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Your current password is not correct.");
        }
        loginAttemptService.recordSuccess(lockKey);

        ParentAccount account = accountRepository.findBySchoolIdAndPhone(ctx.schoolId, ctx.phone).orElseGet(() -> {
            ParentAccount fresh = new ParentAccount();
            fresh.setSchoolId(ctx.schoolId);
            fresh.setPhone(ctx.phone);
            return fresh;
        });
        account.setPasswordHash(PasswordHashUtil.hash(newPassword));
        account.setPasswordChangedAt(Instant.now());
        accountRepository.save(account);
        return ResponseEntity.ok(Collections.singletonMap("message", "Password changed."));
    }

    // ───────────── helpers ─────────────

    /** Personal password if the parent changed it, otherwise the school's default. */
    private boolean passwordOk(String schoolId, String tail, String password) {
        ParentAccount account = accountRepository.findBySchoolIdAndPhone(schoolId, tail).orElse(null);
        boolean personal = account != null && account.getPasswordHash() != null && !account.getPasswordHash().isEmpty();
        String hash = personal ? account.getPasswordHash()
                : configRepository.findBySchoolId(schoolId).map(c -> c.getDefaultPasswordHash()).orElse(null);
        if (hash == null || hash.isEmpty() || !PasswordHashUtil.verify(password, hash)) return false;

        if (PasswordHashUtil.needsRehash(hash)) {
            String upgraded = PasswordHashUtil.hash(password);
            if (personal) {
                account.setPasswordHash(upgraded);
                accountRepository.save(account);
            } else {
                configRepository.findBySchoolId(schoolId).ifPresent(c -> {
                    c.setDefaultPasswordHash(upgraded);
                    configRepository.save(c);
                });
            }
        }
        return true;
    }

    private Map<String, Object> schoolBrief(String schoolId) {
        School school = schoolRepository.findBySchoolId(schoolId).orElse(null);
        SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        String settingsName = ss == null ? null : ss.getSchoolName();
        m.put("schoolId", schoolId);
        m.put("name", settingsName != null && !settingsName.isBlank() ? settingsName : (school == null ? null : school.getName()));
        m.put("logo", school == null ? null : school.getLogo());
        m.put("address", ss == null ? null : ss.getSchoolAddress());
        m.put("phone", ss == null ? null : ss.getSchoolPhone());
        return m;
    }

    private ResponseEntity<?> failed(String lockKey) {
        LoginAttemptService.FailureResult r = loginAttemptService.recordFailure(lockKey);
        if (r.justLocked) return tooManyAttempts(r.retryAfterSeconds);
        String message = "Invalid phone number or password.";
        if (r.attemptCount >= 3) {
            message += " You have made " + r.attemptCount + " login attempts. " + r.attemptsLeft
                    + " attempt" + (r.attemptsLeft == 1 ? "" : "s") + " left before your account is locked.";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("attemptCount", r.attemptCount);
        body.put("attemptsLeft", r.attemptsLeft);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private ResponseEntity<?> tooManyAttempts(long retryAfterSeconds) {
        long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "You have reached the maximum login attempts (" + LoginAttemptService.MAX_ATTEMPTS
                + "). Try again after " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
        body.put("locked", true);
        body.put("retryAfterSeconds", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }
}
