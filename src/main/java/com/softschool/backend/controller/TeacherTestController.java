package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.softschool.backend.model.TeacherTest;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.repository.TeacherTestRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.security.TeacherAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Quizzes / tests created in the Teacher Portal (questions + checked results).
 *
 *   GET    /api/teacher-tests?schoolId=[&staffId=]   teacher: her own | admin: whole school or one teacher
 *   PUT    /api/teacher-tests/{testId}               teacher only: create or update one of her tests
 *   DELETE /api/teacher-tests/{testId}?schoolId=     teacher only: delete one of her tests
 *
 * A teacher can only ever see or change tests under her own staffId — the id
 * comes from the signed token, never from the request.
 */
@RestController
@RequestMapping("/api/teacher-tests")
@CrossOrigin(origins = "*")
public class TeacherTestController {

    private static final int MAX_PAYLOAD_CHARS = 600_000;
    private static final int MAX_QUESTIONS = 200;
    private static final int MAX_TESTS_PER_TEACHER = 500;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    @Autowired private TeacherTestRepository testRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private SchoolSessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String schoolId,
                                  @RequestParam(required = false) String staffId,
                                  HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        List<TeacherTest> rows;
        if (TeacherAccessGuard.isTeacher(p)) {
            rows = testRepository.findBySchoolIdAndStaffIdOrderByUpdatedAtDesc(p.getSchoolId(), staffIdOf(p));
        } else if (staffId != null && !staffId.isBlank()) {
            rows = testRepository.findBySchoolIdAndStaffIdOrderByUpdatedAtDesc(p.getSchoolId(), staffId.trim());
        } else {
            rows = testRepository.findBySchoolIdOrderByUpdatedAtDesc(p.getSchoolId());
        }

        ArrayNode out = mapper.createArrayNode();
        for (TeacherTest t : rows) {
            try {
                JsonNode node = mapper.readTree(t.getPayloadJson());
                if (node.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("staffId", t.getStaffId());
                out.add(node);
            } catch (Exception ignored) { /* skip a corrupt row rather than fail the list */ }
        }
        return ResponseEntity.ok(out);
    }

    @PutMapping("/{testId}")
    @Transactional
    public ResponseEntity<?> upsert(@PathVariable String testId, @RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (!TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Teacher accounts only.");
        if (!ID.matcher(testId).matches()) return error(HttpStatus.BAD_REQUEST, "Invalid test id.");
        if (!body.isObject() || !testId.equals(body.path("id").asText(""))) {
            return error(HttpStatus.BAD_REQUEST, "Test id in the body must match the URL.");
        }

        String staffId = staffIdOf(p);
        var teacher = staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
        if (teacher == null || !"Teaching".equalsIgnoreCase(teacher.getType())) {
            return error(HttpStatus.FORBIDDEN, "This teacher account is no longer active.");
        }
        // New paper tests carry totalMarks; old quizzes carry a questions array. Either is accepted.
        JsonNode questions = body.path("questions");
        boolean legacy = questions.isArray() && questions.size() > 0;
        if (legacy && questions.size() > MAX_QUESTIONS) return error(HttpStatus.BAD_REQUEST, "Too many questions (max " + MAX_QUESTIONS + ").");
        double total = body.path("totalMarks").asDouble(0);
        if (!legacy && total <= 0) return error(HttpStatus.BAD_REQUEST, "Total marks must be greater than 0.");
        if (body.path("title").asText("").isBlank()) return error(HttpStatus.BAD_REQUEST, "Test name is required.");
        if (body.path("cls").asText("").isBlank() || body.path("subject").asText("").isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Class and subject are required.");
        }
        JsonNode results = body.path("results");
        if (results.isObject()) {
            if (results.size() > 3000) return error(HttpStatus.BAD_REQUEST, "Too many results.");
            var it = results.fields();
            while (it.hasNext()) {
                var e = it.next();
                double sc = e.getValue().path("score").asDouble(0);
                if (e.getKey().length() > 64 || sc < 0 || (!legacy && sc > total)) {
                    return error(HttpStatus.BAD_REQUEST, "A mark is outside 0 - " + (long) total + ".");
                }
            }
        }

        String payload;
        try { var copy = ((com.fasterxml.jackson.databind.node.ObjectNode) body).deepCopy(); copy.remove(java.util.List.of("staffId", "staffName")); payload = mapper.writeValueAsString(copy); } catch (Exception e) { return error(HttpStatus.BAD_REQUEST, "Invalid test data."); }
        if (payload.length() > MAX_PAYLOAD_CHARS) return error(HttpStatus.PAYLOAD_TOO_LARGE, "This test is too large to save.");

        TeacherTest t = testRepository.findBySchoolIdAndStaffIdAndTestId(p.getSchoolId(), staffId, testId).orElse(null);
        // New tests (or a changed class/subject) must be for a subject the teacher teaches in that class.
        // Saving marks on an existing test is left alone, even if the admin changed assignments since.
        String newCls = clip(body.path("cls").asText(""), 100), newSubject = clip(body.path("subject").asText(""), 100);
        boolean sameTarget = t != null && newCls.equals(t.getClassName()) && newSubject.equals(t.getSubject());
        if (!sameTarget && !com.softschool.backend.service.SubjectAssignments.teachesSubjectInClass(teacher, newSubject, newCls)) {
            return error(HttpStatus.FORBIDDEN, "You are not assigned to teach " + newSubject + " in " + newCls + ".");
        }
        if (t == null) {
            if (testRepository.countBySchoolIdAndStaffId(p.getSchoolId(), staffId) >= MAX_TESTS_PER_TEACHER) {
                return error(HttpStatus.CONFLICT, "Test limit reached. Delete old tests first.");
            }
            t = new TeacherTest();
            t.setSchoolId(p.getSchoolId());
            t.setStaffId(staffId);
            t.setTestId(testId);
        }
        t.setTitle(clip(body.path("title").asText(""), 200));
        t.setClassName(clip(body.path("cls").asText(""), 100));
        t.setSubject(clip(body.path("subject").asText(""), 100));
        t.setType("quiz".equalsIgnoreCase(body.path("kind").asText(body.path("type").asText(""))) ? "quiz" : "test");
        t.setPayloadJson(payload);
        t.setUpdatedAt(Instant.now());
        testRepository.save(t);
        return ResponseEntity.ok(Collections.singletonMap("saved", true));
    }

    @DeleteMapping("/{testId}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable String testId, @RequestParam String schoolId, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (!TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Teacher accounts only.");
        testRepository.deleteBySchoolIdAndStaffIdAndTestId(p.getSchoolId(), staffIdOf(p), testId);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──
    private SchoolSessionService.Principal principal(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return sessionService.verifyToken(h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null);
    }
    private String staffIdOf(SchoolSessionService.Principal p) { return p.getUsername().substring(TeacherAccessGuard.PREFIX.length()); }
    private String clip(String s, int max) { return s == null ? "" : (s.length() > max ? s.substring(0, max) : s); }
    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
