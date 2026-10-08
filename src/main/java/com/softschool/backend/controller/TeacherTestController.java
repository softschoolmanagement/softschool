package com.softschool.teacherportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Tests / papers a teacher adds, plus the marks they enter.
 *   GET    /api/teacher-tests?schoolId=&staffId=     -> [ {test}, ... ]   (teacher: own tests; admin: whole school, or one teacher with staffId)
 *   PUT    /api/teacher-tests/{id}                   -> create or replace one test (idempotent, so the app can safely retry)
 *   DELETE /api/teacher-tests/{id}?schoolId=&staffId=
 *
 * Test JSON (exactly what teacher-portal.js sends):
 *   { id, kind, title, cls, subject, date, totalMarks, passPct, syllabus, marked, created,
 *     results: { "<regNo>": { absent:boolean, score:number, at:number } } }
 */
@RestController
@RequestMapping("/api/teacher-tests")
public class TeacherTestController {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final int MAX_PAYLOAD = 600_000;   // ~600 KB: a class of 200 students is ~20 KB

    private final TeacherTestRepository repo;
    private final ObjectMapper om;

    public TeacherTestController(TeacherTestRepository repo, ObjectMapper om) { this.repo = repo; this.om = om; }

    @GetMapping
    public List<JsonNode> list(HttpServletRequest req, @RequestParam(required = false) String staffId) throws Exception {
        String school = TeacherPortalAuth.schoolId(req);
        List<TeacherTest> rows = TeacherPortalAuth.isTeacher(req)
                ? repo.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(school, TeacherPortalAuth.staffId(req))
                : (staffId != null && !staffId.isBlank()
                        ? repo.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(school, staffId.trim())
                        : repo.findBySchoolIdOrderByCreatedAtDesc(school));
        List<JsonNode> out = new ArrayList<>();
        for (TeacherTest t : rows) {
            ObjectNode n = (ObjectNode) om.readTree(t.payload);
            n.put("id", t.id);
            n.put("staffId", t.staffId);
            n.put("staffName", t.staffName);
            out.add(n);
        }
        return out;
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> put(HttpServletRequest req, @PathVariable String id, @RequestBody ObjectNode body) throws Exception {
        String school = TeacherPortalAuth.schoolId(req);
        String staff = TeacherPortalAuth.actingStaff(req, text(body, "staffId"));
        if (!ID.matcher(id).matches()) throw bad("Invalid test id");

        String title = text(body, "title"), cls = text(body, "cls"), subject = text(body, "subject");
        if (title == null || title.isBlank()) throw bad("Test name is required");
        if (cls == null || cls.isBlank()) throw bad("Class is required");
        if (subject == null || subject.isBlank()) throw bad("Subject is required");
        double total = body.path("totalMarks").asDouble(0);
        boolean legacy = body.has("questions");                       // tests made by the old question builder
        if (total <= 0 && !legacy) throw bad("Total marks must be greater than 0");

        // marks sanity: 0 <= score <= totalMarks, and no more than a school-sized number of rows
        JsonNode results = body.path("results");
        if (results.isObject()) {
            if (results.size() > 3000) throw bad("Too many results");
            for (Iterator<Map.Entry<String, JsonNode>> it = results.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().length() > 64) throw bad("Invalid registration number");
                double s = e.getValue().path("score").asDouble(0);
                if (s < 0 || (!legacy && s > total)) throw bad("A mark is outside 0 – " + (long) total);
            }
        }

        Optional<TeacherTest> existing = repo.findBySchoolIdAndId(school, id);
        if (existing.isPresent() && !existing.get().staffId.equals(staff) && TeacherPortalAuth.isTeacher(req))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This test belongs to another teacher");

        ObjectNode clean = body.deepCopy();
        clean.remove(Arrays.asList("schoolId", "staffId", "staffName"));   // identity lives in columns, never trusted from the payload
        String payload = om.writeValueAsString(clean);
        if (payload.length() > MAX_PAYLOAD) throw bad("Test is too large");

        long now = System.currentTimeMillis();
        TeacherTest t = existing.orElseGet(TeacherTest::new);
        t.schoolId = school; t.id = id;
        t.staffId = existing.map(x -> x.staffId).orElse(staff);
        t.staffName = clip(text(body, "staffName"), 120);
        t.kind = clip(text(body, "kind"), 30);
        t.title = clip(title.trim(), 120); t.cls = clip(cls.trim(), 60); t.subject = clip(subject.trim(), 80);
        t.testDate = clip(text(body, "date"), 10);
        t.totalMarks = total;
        t.marked = body.path("marked").asBoolean(false);
        t.payload = payload;
        t.createdAt = body.hasNonNull("created") ? body.get("created").asLong() : (t.createdAt != null ? t.createdAt : now);
        t.updatedAt = now;
        repo.save(t);
        return Map.of("ok", true, "id", id, "updatedAt", now);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> delete(HttpServletRequest req, @PathVariable String id) {
        String school = TeacherPortalAuth.schoolId(req);
        Optional<TeacherTest> t = repo.findBySchoolIdAndId(school, id);
        if (t.isEmpty()) return ResponseEntity.ok(Map.of("ok", true));        // already gone: deleting twice is fine
        if (TeacherPortalAuth.isTeacher(req) && !t.get().staffId.equals(TeacherPortalAuth.staffId(req)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This test belongs to another teacher");
        repo.deleteBySchoolIdAndId(school, id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String text(JsonNode n, String f) { JsonNode v = n.get(f); return v == null || v.isNull() ? null : v.asText(); }
    private static String clip(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
