package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.softschool.backend.model.*;
import com.softschool.backend.repository.*;
import com.softschool.backend.security.PasswordHashUtil;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.security.TeacherAccessGuard;
import com.softschool.backend.service.FileStorageService;
import com.softschool.backend.service.ParentContext;
import com.softschool.backend.service.ParentUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * School-admin side of the parent portal. Admin session only (the school's own login token): teacher
 * and parent tokens are refused here, and the school always comes from the token.
 *
 *   GET    /api/parent-admin/default-password              { set }
 *   PUT    /api/parent-admin/default-password              { password }          the password every parent starts with
 *   POST   /api/parent-admin/accounts/reset                { phone }             parent forgot it: back to the default
 *   GET    /api/parent-admin/submissions[?kind=]           feedback / leave / complaints / suggestions
 *   PUT    /api/parent-admin/submissions/{id}              { status: new|seen|resolved, adminNote? }
 *   GET    /api/parent-admin/submissions/{id}/image/{n}
 *   PUT    /api/parent-admin/timetable/{className}         body = the timetable JSON (see ClassTimetable)
 *   DELETE /api/parent-admin/timetable/{className}
 *   PUT    /api/parent-admin/results                       { session, term, results:[{regNo, subjects:[{name,total,obtained}]}] }
 */
@RestController
@RequestMapping("/api/parent-admin")
public class ParentAdminController {

    private static final int MAX_TIMETABLE_CHARS = 100_000;
    private static final int MAX_RESULT_ROWS = 2000;

    @Autowired private SchoolSessionService sessionService;
    @Autowired private ParentPortalConfigRepository configRepository;
    @Autowired private ParentAccountRepository accountRepository;
    @Autowired private ParentSubmissionRepository submissionRepository;
    @Autowired private ClassTimetableRepository timetableRepository;
    @Autowired private ExamResultRepository resultRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private FileStorageService fileStorageService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ───────────── PASSWORDS ─────────────
    @GetMapping("/default-password")
    public ResponseEntity<?> passwordStatus(HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        boolean set = configRepository.findBySchoolId(p.getSchoolId())
                .map(c -> c.getDefaultPasswordHash() != null && !c.getDefaultPasswordHash().isEmpty()).orElse(false);
        return ResponseEntity.ok(Collections.singletonMap("set", set));
    }

    @PutMapping("/default-password")
    public ResponseEntity<?> setPassword(@RequestBody Map<String, String> body, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        String password = body == null ? null : body.get("password");
        if (password == null || password.length() < 6) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters.");
        if (password.length() > 100) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Password is too long.");
        ParentPortalConfig cfg = configRepository.findBySchoolId(p.getSchoolId()).orElseGet(() -> {
            ParentPortalConfig fresh = new ParentPortalConfig();
            fresh.setSchoolId(p.getSchoolId());
            return fresh;
        });
        cfg.setDefaultPasswordHash(PasswordHashUtil.hash(password));
        configRepository.save(cfg);
        return ResponseEntity.ok(Collections.singletonMap("message", "Default parent password saved."));
    }

    /** Clears a parent's own password, so they sign in with the school's default again. */
    @PostMapping("/accounts/reset")
    @Transactional
    public ResponseEntity<?> resetAccount(@RequestBody Map<String, String> body, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        String tail = ParentUtil.tail10(body == null ? null : body.get("phone"));
        if (tail == null) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Enter the parent's phone number.");
        ParentAccount a = accountRepository.findBySchoolIdAndPhone(p.getSchoolId(), tail).orElse(null);
        if (a != null) {
            a.setPasswordHash(null);
            a.setPasswordChangedAt(null);
            accountRepository.save(a);
        }
        return ResponseEntity.ok(Collections.singletonMap("reset", true));
    }

    // ───────────── SUBMISSIONS ─────────────
    @GetMapping("/submissions")
    public ResponseEntity<?> submissions(@RequestParam(required = false) String kind, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        List<ParentSubmission> rows = kind != null && !kind.isBlank()
                ? submissionRepository.findBySchoolIdAndKindOrderByCreatedAtDesc(p.getSchoolId(), kind.trim().toLowerCase(), PageRequest.of(0, 200))
                : submissionRepository.findBySchoolIdOrderByCreatedAtDesc(p.getSchoolId(), PageRequest.of(0, 200));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParentSubmission s : rows) {
            Map<String, Object> m = ParentSubmissionController.view(s);
            m.put("phone", s.getPhone());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @PutMapping("/submissions/{id}")
    @Transactional
    public ResponseEntity<?> updateSubmission(@PathVariable Long id, @RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        ParentSubmission s = submissionRepository.findByIdAndSchoolId(id, p.getSchoolId()).orElse(null);
        if (s == null) return ParentUtil.error(HttpStatus.NOT_FOUND, "Not found.");
        String status = body.path("status").asText("").trim().toLowerCase();
        if (!status.isEmpty()) {
            if (!Arrays.asList("new", "seen", "resolved").contains(status)) return ParentUtil.error(HttpStatus.BAD_REQUEST, "status must be new, seen or resolved.");
            s.setStatus(status);
        }
        if (body.has("adminNote")) s.setAdminNote(ParentUtil.clip(body.path("adminNote").asText(""), 500));
        submissionRepository.save(s);
        return ResponseEntity.ok(ParentSubmissionController.view(s));
    }

    @GetMapping("/submissions/{id}/image/{n}")
    public ResponseEntity<?> submissionImage(@PathVariable Long id, @PathVariable int n, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        ParentSubmission s = submissionRepository.findByIdAndSchoolId(id, p.getSchoolId()).orElse(null);
        if (s == null || s.getImagePaths() == null || s.getImagePaths().isBlank()) return ResponseEntity.notFound().build();
        String[] paths = s.getImagePaths().split("\n");
        if (n < 0 || n >= paths.length) return ResponseEntity.notFound().build();
        try {
            Path file = fileStorageService.resolve(paths[n]);
            if (!Files.exists(file)) return ResponseEntity.notFound().build();
            byte[] bytes = Files.readAllBytes(file);
            String type = FileStorageService.detectMediaType(bytes);
            if (type == null || !type.startsWith("image/")) return ResponseEntity.notFound().build();
            return ResponseEntity.ok().header("Content-Type", type)
                    .header("Cache-Control", "private, max-age=3600")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ───────────── TIMETABLE ─────────────
    @PutMapping("/timetable/{className}")
    @Transactional
    public ResponseEntity<?> putTimetable(@PathVariable String className, @RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        String cls = ParentUtil.clip(className, 100);
        if (cls.isEmpty()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "className is required.");
        if (!body.isObject() || !body.path("days").isObject()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "The timetable needs a \"days\" object.");
        String payload;
        try { payload = mapper.writeValueAsString(body); } catch (Exception e) { return ParentUtil.error(HttpStatus.BAD_REQUEST, "Invalid timetable."); }
        if (payload.length() > MAX_TIMETABLE_CHARS) return ParentUtil.error(HttpStatus.PAYLOAD_TOO_LARGE, "This timetable is too large.");

        ClassTimetable t = timetableRepository.findBySchoolIdAndClassNameIgnoreCase(p.getSchoolId(), cls).orElseGet(() -> {
            ClassTimetable fresh = new ClassTimetable();
            fresh.setSchoolId(p.getSchoolId());
            fresh.setClassName(cls);
            return fresh;
        });
        t.setPayloadJson(payload);
        t.setUpdatedAt(Instant.now());
        timetableRepository.save(t);
        return ResponseEntity.ok(Collections.singletonMap("saved", true));
    }

    @DeleteMapping("/timetable/{className}")
    @Transactional
    public ResponseEntity<?> deleteTimetable(@PathVariable String className, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        timetableRepository.findBySchoolIdAndClassNameIgnoreCase(p.getSchoolId(), className.trim()).ifPresent(timetableRepository::delete);
        return ResponseEntity.noContent().build();
    }

    // ───────────── RESULTS ─────────────
    @PutMapping("/results")
    @Transactional
    public ResponseEntity<?> putResults(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = admin(req);
        if (p == null) return denied(req);
        String session = body.path("session").asText("").trim();
        String term = body.path("term").asText("").trim();
        if (!session.matches("[0-9A-Za-z_\\-]{1,20}") || !term.matches("[0-9A-Za-z_\\-]{1,40}")) {
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "session (e.g. 2025-2026) and term (e.g. first-term) are required.");
        }
        JsonNode rows = body.path("results");
        if (!rows.isArray() || rows.size() == 0) return ParentUtil.error(HttpStatus.BAD_REQUEST, "results must be a non-empty list.");
        if (rows.size() > MAX_RESULT_ROWS) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Too many results in one request (max " + MAX_RESULT_ROWS + ").");

        int saved = 0;
        for (JsonNode r : rows) {
            String regNo = r.path("regNo").asText("").trim();
            if (regNo.isEmpty() || studentRepository.findByRegNoAndSchoolId(regNo, p.getSchoolId()).isEmpty()) {
                return ParentUtil.error(HttpStatus.BAD_REQUEST, "Student \"" + ParentUtil.clip(regNo, 40) + "\" was not found in this school.");
            }
            JsonNode subjects = r.path("subjects");
            if (!subjects.isArray() || subjects.size() == 0 || subjects.size() > 40) {
                return ParentUtil.error(HttpStatus.BAD_REQUEST, "Every student needs 1 to 40 subjects (" + regNo + ").");
            }
            ObjectNode payload = mapper.createObjectNode();
            var arr = payload.putArray("subjects");
            for (JsonNode s : subjects) {
                String name = ParentUtil.clip(s.path("name").asText(""), 60);
                double total = s.path("total").asDouble(0), obtained = s.path("obtained").asDouble(-1);
                if (name.isEmpty() || total <= 0 || obtained < 0 || obtained > total) {
                    return ParentUtil.error(HttpStatus.BAD_REQUEST, "A mark is outside 0 - total (" + regNo + ", " + name + ").");
                }
                ObjectNode o = arr.addObject();
                o.put("name", name);
                o.put("total", total);
                o.put("obtained", obtained);
            }
            ExamResult e = resultRepository.findBySchoolIdAndRegNoAndSessionAndTerm(p.getSchoolId(), regNo, session, term).orElseGet(() -> {
                ExamResult fresh = new ExamResult();
                fresh.setSchoolId(p.getSchoolId());
                fresh.setRegNo(regNo);
                fresh.setSession(session);
                fresh.setTerm(term);
                return fresh;
            });
            try { e.setPayloadJson(mapper.writeValueAsString(payload)); } catch (Exception ex) { return ParentUtil.error(HttpStatus.BAD_REQUEST, "Invalid result data."); }
            e.setUpdatedAt(Instant.now());
            resultRepository.save(e);
            saved++;
        }
        return ResponseEntity.ok(Collections.singletonMap("saved", saved));
    }

    // ───────────── helpers ─────────────

    /** The school's own admin session — never a teacher or a parent. */
    private SchoolSessionService.Principal admin(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        SchoolSessionService.Principal p = sessionService.verifyToken(h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null);
        if (p == null || TeacherAccessGuard.isTeacher(p) || ParentContext.isParent(p)) return null;
        return p;
    }

    private ResponseEntity<?> denied(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        SchoolSessionService.Principal p = sessionService.verifyToken(h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null);
        return p == null ? ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.")
                         : ParentUtil.error(HttpStatus.FORBIDDEN, "Admin only.");
    }
}
