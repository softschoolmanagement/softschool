package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Announcement;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.AnnouncementRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.security.TeacherAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Announcements between the admin and the Teacher Portal.
 *
 *   GET    /api/announcements?schoolId=        teacher: notices for her | admin: everything
 *   GET    /api/announcements/inbox?schoolId=  admin only: messages teachers sent to admin
 *   POST   /api/announcements                  admin only: post a notice
 *   POST   /api/announcements/teacher          teacher only: send from the portal
 *   DELETE /api/announcements/{id}?schoolId=   admin only
 *
 * The school boundary is enforced by SchoolAuthFilter (schoolId must match the
 * token). What a teacher token may call is enforced by TeacherAccessGuard; the
 * role checks below are a second line of defence.
 */
@RestController
@RequestMapping("/api/announcements")
@CrossOrigin(origins = "*")
public class AnnouncementController {

    private static final int MAX_LIST = 100;
    private static final int TEACHER_DAILY_LIMIT = 20;

    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private SchoolSessionService sessionService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ───────────── READ ─────────────

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String schoolId, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        List<Announcement> rows;
        if (TeacherAccessGuard.isTeacher(p)) {
            String staffId = staffIdOf(p);
            rows = announcementRepository.findVisibleToTeacher(p.getSchoolId(), staffId, PageRequest.of(0, MAX_LIST));
        } else {
            rows = announcementRepository.findBySchoolIdOrderByCreatedAtDesc(p.getSchoolId(), PageRequest.of(0, MAX_LIST));
        }
        return ResponseEntity.ok(rows.stream().map(this::view).collect(Collectors.toList()));
    }

    @GetMapping("/inbox")
    public ResponseEntity<?> adminInbox(@RequestParam String schoolId, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Admin only.");
        List<Announcement> rows = announcementRepository.findBySchoolIdAndAudienceOrderByCreatedAtDesc(
                p.getSchoolId(), Announcement.AUD_ADMIN, PageRequest.of(0, MAX_LIST));
        return ResponseEntity.ok(rows.stream().map(this::view).collect(Collectors.toList()));
    }

    // ───────────── ADMIN POSTS ─────────────

    @PostMapping
    public ResponseEntity<?> adminPost(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Only the admin can post announcements.");

        String audience = text(body, "audience", 10).toLowerCase();
        if (audience.isEmpty()) audience = Announcement.AUD_TEACHERS;
        if (!Set.of(Announcement.AUD_TEACHERS, Announcement.AUD_TEACHER, Announcement.AUD_CLASS).contains(audience)) {
            return error(HttpStatus.BAD_REQUEST, "audience must be teachers, teacher or class.");
        }
        String title = text(body, "title", 120), msg = text(body, "body", 2000);
        if (title.isEmpty() || msg.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Title and message are required.");

        Announcement a = new Announcement();
        a.setSchoolId(p.getSchoolId());
        a.setSenderType(Announcement.SENDER_ADMIN);
        a.setSenderId("admin");
        String sender = text(body, "senderName", 80);
        a.setSenderName(sender.isEmpty() ? "Admin" : sender);
        a.setAudience(audience);
        a.setTitle(title);
        a.setBody(msg);
        a.setPriority(priority(body));

        if (Announcement.AUD_TEACHER.equals(audience)) {
            String target = text(body, "targetStaffId", 60);
            Staff t = target.isEmpty() ? null : staffRepository.findByStaffIdAndSchoolId(target, p.getSchoolId()).orElse(null);
            if (t == null) return error(HttpStatus.BAD_REQUEST, "That teacher was not found in this school.");
            a.setTargetStaffId(t.getStaffId());
        }
        if (Announcement.AUD_CLASS.equals(audience)) {
            String cls = text(body, "className", 80);
            if (cls.isEmpty()) return error(HttpStatus.BAD_REQUEST, "className is required for a class announcement.");
            a.setClassName(cls);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view(announcementRepository.save(a)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam String schoolId, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Admin only.");
        return announcementRepository.findByIdAndSchoolId(id, p.getSchoolId()).map(a -> {
            announcementRepository.delete(a);
            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    // ───────────── TEACHER SENDS ─────────────

    @PostMapping("/teacher")
    public ResponseEntity<?> teacherSend(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (!TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Teacher accounts only.");

        String staffId = staffIdOf(p);
        // Never trust the staffId/staffName in the body — the token decides who is sending.
        Staff teacher = staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
        if (teacher == null || !"Teaching".equalsIgnoreCase(teacher.getType())) {
            return error(HttpStatus.FORBIDDEN, "This teacher account is no longer active.");
        }

        String audience = text(body, "audience", 10).toLowerCase();
        if (!Set.of(Announcement.AUD_ADMIN, Announcement.AUD_TEACHERS, Announcement.AUD_CLASS).contains(audience)) {
            return error(HttpStatus.BAD_REQUEST, "audience must be admin, teachers or class.");
        }
        String title = text(body, "title", 120), msg = text(body, "body", 2000);
        if (title.isEmpty() || msg.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Title and message are required.");

        String cls = text(body, "className", 80);
        if (Announcement.AUD_CLASS.equals(audience) && !teachesClass(teacher, cls)) {
            return error(HttpStatus.FORBIDDEN, "You can only message classes you teach.");
        }

        long today = announcementRepository.countBySchoolIdAndSenderTypeAndSenderIdAndCreatedAtAfter(
                p.getSchoolId(), Announcement.SENDER_TEACHER, staffId, Instant.now().minus(1, ChronoUnit.DAYS));
        if (today >= TEACHER_DAILY_LIMIT) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Daily message limit reached. Try again tomorrow.");
        }

        Announcement a = new Announcement();
        a.setSchoolId(p.getSchoolId());
        a.setSenderType(Announcement.SENDER_TEACHER);
        a.setSenderId(staffId);
        a.setSenderName(teacher.getName());
        a.setAudience(audience);
        a.setClassName(Announcement.AUD_CLASS.equals(audience) ? cls : null);
        a.setTitle(title);
        a.setBody(msg);
        a.setPriority(priority(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(announcementRepository.save(a)));
    }

    // ───────────── helpers ─────────────

    private Map<String, Object> view(Announcement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("body", a.getBody());
        m.put("from", a.getSenderName() == null || a.getSenderName().isBlank() ? "Admin" : a.getSenderName());
        m.put("senderType", a.getSenderType());
        m.put("audience", a.getAudience());
        m.put("className", a.getClassName());
        m.put("targetStaffId", a.getTargetStaffId());
        m.put("priority", a.getPriority());
        m.put("date", a.getCreatedAt().toString()); // ISO-8601 UTC, e.g. 2026-10-06T09:30:00Z
        return m;
    }

    private SchoolSessionService.Principal principal(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
        return sessionService.verifyToken(token);
    }

    private String staffIdOf(SchoolSessionService.Principal p) {
        return p.getUsername().substring(TeacherAccessGuard.PREFIX.length());
    }

    private String text(JsonNode n, String field, int max) {
        String v = n.path(field).asText("").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private String priority(JsonNode n) {
        return "urgent".equalsIgnoreCase(n.path("priority").asText("")) ? "urgent" : "normal";
    }

    /** True when the class (e.g. "Class 5 - A" or "Class 5") is one the teacher teaches or is incharge of. */
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

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
