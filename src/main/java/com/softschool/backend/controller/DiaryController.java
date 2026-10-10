package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.DiaryEntry;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.DiaryEntryRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.security.TeacherAccessGuard;
import com.softschool.backend.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Homework diary: a teacher picks class -> subject -> uploads pictures of the diary.
 *
 *   POST   /api/diary                        teacher only, multipart: className, subject, note?, date?, images[1..6]
 *   GET    /api/diary[?staffId=]             teacher: her own | admin: whole school (or one teacher)
 *   GET    /api/diary/class?className=       entries of one class (admin / a teacher of that class) — parent portal feed
 *   GET    /api/diary/{id}/image/{n}         the n-th picture (0-based), served as bytes
 *   DELETE /api/diary/{id}                   the teacher who posted it, or the admin
 *
 * PARENT PORTAL (later): the parent portal will call GET /api/diary/class and
 * GET /api/diary/{id}/image/{n} with a PARENT-scoped token. Add that principal
 * type to canReadClass() below — nothing else here has to change.
 */
@RestController
@RequestMapping("/api/diary")
@CrossOrigin(origins = "*")
public class DiaryController {

    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int TEACHER_DAILY_LIMIT = 30;
    private static final int MAX_LIST = 100;

    @Autowired private DiaryEntryRepository diaryRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private SchoolSessionService sessionService;
    @Autowired private FileStorageService fileStorageService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ───────────── POST ─────────────
    @PostMapping(consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<?> create(@RequestParam String className,
                                    @RequestParam String subject,
                                    @RequestParam(required = false) String note,
                                    @RequestParam(required = false) String date,
                                    @RequestParam(required = false) String schoolId,
                                    @RequestParam("images") List<MultipartFile> images,
                                    HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (!TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Only teachers can upload the diary.");
        if (schoolId != null && !schoolId.isBlank() && !schoolId.trim().equals(p.getSchoolId())) {
            return error(HttpStatus.FORBIDDEN, "The requested school does not match the authenticated school.");
        }

        String staffId = staffIdOf(p);
        Staff teacher = staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
        if (teacher == null || !"Teaching".equalsIgnoreCase(teacher.getType())) {
            return error(HttpStatus.FORBIDDEN, "This teacher account is no longer active.");
        }
        String cls = clip(className, 100), sub = clip(subject, 80);
        if (cls.isEmpty() || sub.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Choose the class and the subject.");
        if (!teachesClass(teacher, cls)) return error(HttpStatus.FORBIDDEN, "You can only send diary for classes you teach.");
        if (!teachesSubject(teacher, sub)) return error(HttpStatus.FORBIDDEN, "That subject is not assigned to you.");
        if (!com.softschool.backend.service.SubjectAssignments.teachesSubjectInClass(teacher, sub, cls)) {
            return error(HttpStatus.FORBIDDEN, "You are not assigned to teach " + sub + " in " + cls + ".");
        }

        List<MultipartFile> files = new ArrayList<>();
        for (MultipartFile f : images) if (f != null && !f.isEmpty()) files.add(f);
        if (files.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Add at least one picture.");
        if (files.size() > MAX_IMAGES) return error(HttpStatus.BAD_REQUEST, "You can send up to " + MAX_IMAGES + " pictures.");
        for (MultipartFile f : files) {
            String type = f.getContentType() == null ? "" : f.getContentType().toLowerCase();
            if (!type.startsWith("image/")) return error(HttpStatus.BAD_REQUEST, "Only pictures can be uploaded.");
            if (f.getSize() > MAX_IMAGE_BYTES) return error(HttpStatus.PAYLOAD_TOO_LARGE, "Each picture must be under 5 MB.");
        }

        long today = diaryRepository.countBySchoolIdAndStaffIdAndCreatedAtAfter(p.getSchoolId(), staffId, Instant.now().minus(1, ChronoUnit.DAYS));
        if (today >= TEACHER_DAILY_LIMIT) return error(HttpStatus.TOO_MANY_REQUESTS, "Daily diary limit reached. Try again tomorrow.");

        List<String> stored = new ArrayList<>();
        try {
            for (MultipartFile f : files) stored.add(fileStorageService.storeDiaryImage(f));
        } catch (IllegalArgumentException | IOException | SecurityException e) {
            for (String s : stored) fileStorageService.deleteQuietly(s);          // all or nothing
            return error(HttpStatus.BAD_REQUEST, "Could not save a picture: " + e.getMessage());
        }

        DiaryEntry d = new DiaryEntry();
        d.setSchoolId(p.getSchoolId());
        d.setStaffId(staffId);
        d.setStaffName(teacher.getName());
        d.setClassName(cls);
        d.setSubject(sub);
        d.setNote(note == null || note.isBlank() ? null : clip(note, 500));
        d.setDiaryDate(diaryDate(date));
        d.setImagePaths(String.join("\n", stored));
        d.setPublished(true);
        diaryRepository.save(d);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(d));
    }

    // ───────────── READ ─────────────
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String schoolId,
                                  @RequestParam(required = false) String staffId,
                                  HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (schoolId != null && !schoolId.isBlank() && !schoolId.trim().equals(p.getSchoolId())) {
            return error(HttpStatus.FORBIDDEN, "The requested school does not match the authenticated school.");
        }
        List<DiaryEntry> rows;
        if (TeacherAccessGuard.isTeacher(p)) {
            rows = diaryRepository.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(p.getSchoolId(), staffIdOf(p), PageRequest.of(0, MAX_LIST));
        } else if (staffId != null && !staffId.isBlank()) {
            rows = diaryRepository.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(p.getSchoolId(), staffId.trim(), PageRequest.of(0, MAX_LIST));
        } else {
            rows = diaryRepository.findBySchoolIdOrderByCreatedAtDesc(p.getSchoolId(), PageRequest.of(0, MAX_LIST));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiaryEntry d : rows) out.add(view(d));
        return ResponseEntity.ok(out);
    }

    /** Feed of one class — this is what the parent portal will read. */
    @GetMapping("/class")
    public ResponseEntity<?> byClass(@RequestParam String className, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (!canReadClass(p, className)) return error(HttpStatus.FORBIDDEN, "You cannot view this class's diary.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiaryEntry d : diaryRepository.findBySchoolIdAndClassNameAndPublishedTrueOrderByCreatedAtDesc(
                p.getSchoolId(), className.trim(), PageRequest.of(0, MAX_LIST))) out.add(view(d));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}/image/{n}")
    public ResponseEntity<?> image(@PathVariable Long id, @PathVariable int n, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        DiaryEntry d = diaryRepository.findByIdAndSchoolId(id, p.getSchoolId()).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        boolean own = TeacherAccessGuard.isTeacher(p) && d.getStaffId().equals(staffIdOf(p));
        if (!own && !canReadClass(p, d.getClassName())) return error(HttpStatus.FORBIDDEN, "Not allowed.");
        String[] paths = d.getImagePaths().split("\n");
        if (n < 0 || n >= paths.length) return ResponseEntity.notFound().build();
        Path file = fileStorageService.resolve(paths[n]);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();
        try {
            String name = file.toString().toLowerCase();
            String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : name.endsWith(".gif") ? "image/gif" : "image/jpeg";
            return ResponseEntity.ok().header("Content-Type", type)
                    .header("Cache-Control", "private, max-age=3600")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the picture.");
        }
    }

    // ───────────── DELETE ─────────────
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        DiaryEntry d = diaryRepository.findByIdAndSchoolId(id, p.getSchoolId()).orElse(null);
        if (d == null) return ResponseEntity.noContent().build();                     // already gone
        if (TeacherAccessGuard.isTeacher(p) && !d.getStaffId().equals(staffIdOf(p))) {
            return error(HttpStatus.FORBIDDEN, "This diary belongs to another teacher.");
        }
        for (String path : d.getImagePaths().split("\n")) fileStorageService.deleteQuietly(path);
        diaryRepository.delete(d);
        return ResponseEntity.noContent().build();
    }

    // ───────────── helpers ─────────────
    /** Admin: any class. Teacher: only a class she teaches. PARENT portal: add the parent's class check here. */
    private boolean canReadClass(SchoolSessionService.Principal p, String className) {
        if (!TeacherAccessGuard.isTeacher(p)) return true;
        Staff t = staffRepository.findByStaffIdAndSchoolId(staffIdOf(p), p.getSchoolId()).orElse(null);
        return t != null && teachesClass(t, className);
    }

    private Map<String, Object> view(DiaryEntry d) {
        int count = d.getImagePaths() == null || d.getImagePaths().isBlank() ? 0 : d.getImagePaths().split("\n").length;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("className", d.getClassName());
        m.put("subject", d.getSubject());
        m.put("note", d.getNote());
        m.put("date", d.getDiaryDate());
        m.put("staffId", d.getStaffId());
        m.put("staffName", d.getStaffName());
        m.put("imageCount", count);
        m.put("createdAt", d.getCreatedAt().toString());
        return m;
    }

    private String diaryDate(String date) {
        LocalDate today = LocalDate.now();
        try {
            LocalDate d = LocalDate.parse(date == null ? "" : date.trim());
            if (!d.isBefore(today.minusDays(2)) && !d.isAfter(today.plusDays(1))) return d.toString();
        } catch (Exception ignored) { /* fall back to today */ }
        return today.toString();
    }

    private boolean teachesSubject(Staff s, String subject) {
        if (s.getSubjects() == null || s.getSubjects().isBlank()) return true;    // no list on file: don't block
        for (String x : s.getSubjects().split(",")) if (x.trim().equalsIgnoreCase(subject.trim())) return true;
        return false;
    }

    private boolean teachesClass(Staff s, String label) {
        if (label == null || label.isBlank()) return false;
        String[] parts = label.split("\\s+-\\s+", 2);
        String cls = parts[0].trim(), sec = parts.length > 1 ? parts[1].trim() : "";
        for (String json : new String[]{s.getClassAssignments(), s.getInchargeAssignments()}) {
            try {
                if (json == null || json.isBlank()) continue;
                JsonNode arr = mapper.readTree(json);
                if (!arr.isArray()) continue;
                for (JsonNode n : arr) {
                    String c = n.path("cls").asText("").trim(), sc = n.path("section").asText("").trim();
                    if (c.equalsIgnoreCase(cls) && (sc.isEmpty() || sec.isEmpty() || sc.equalsIgnoreCase(sec))) return true;
                }
            } catch (Exception ignored) { /* try the next source */ }
        }
        if (s.getClasses() != null) {
            for (String c : s.getClasses().split(",")) if (c.trim().equalsIgnoreCase(cls)) return true;
        }
        return s.getAssignedClass() != null && s.getAssignedClass().trim().equalsIgnoreCase(cls);
    }

    private SchoolSessionService.Principal principal(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return sessionService.verifyToken(h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null);
    }
    private String staffIdOf(SchoolSessionService.Principal p) { return p.getUsername().substring(TeacherAccessGuard.PREFIX.length()); }
    private String clip(String s, int max) { String v = s == null ? "" : s.trim(); return v.length() > max ? v.substring(0, max) : v; }
    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
