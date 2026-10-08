package com.softschool.teacherportal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 *  Admin  -> teachers
 *    GET    /api/announcements?schoolId=&audience=teachers      (teachers + admin) newest 100
 *    POST   /api/announcements                                   (admin only) { title, body, priority?, createdBy? }
 *    DELETE /api/announcements/{id}                              (admin only)
 *  Teacher -> admin / parents
 *    POST   /api/announcements/teacher                           (teacher) { audience:"admin"|"parents"|"class", className?, title, body, priority? }
 *    GET    /api/announcements/teacher-messages?audience=admin|parents&className=   (admin inbox, and the parent portal)
 *    POST   /api/announcements/teacher-messages/{id}/read        (admin only)
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {
    private static final int MAX_PER_HOUR = 30;     // a teacher can't flood parents

    private final AnnouncementRepository news;
    private final TeacherMessageRepository msgs;

    public AnnouncementController(AnnouncementRepository news, TeacherMessageRepository msgs) { this.news = news; this.msgs = msgs; }

    /* ───────── admin -> teachers ───────── */
    @GetMapping
    public List<Map<String, Object>> received(HttpServletRequest req, @RequestParam(defaultValue = "teachers") String audience) {
        String school = TeacherPortalAuth.schoolId(req);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Announcement a : news.findBySchoolIdAndAudienceOrderByCreatedAtDesc(school, audience, PageRequest.of(0, 100))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id); m.put("title", a.title); m.put("body", a.body);
            m.put("from", a.createdBy == null || a.createdBy.isBlank() ? "Admin" : a.createdBy);
            m.put("date", java.time.Instant.ofEpochMilli(a.createdAt).toString());
            m.put("priority", a.priority);
            out.add(m);
        }
        return out;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(HttpServletRequest req, @RequestBody ObjectNode b) {
        TeacherPortalAuth.requireAdmin(req);
        Announcement a = new Announcement();
        a.schoolId = TeacherPortalAuth.schoolId(req);
        a.audience = "teachers";
        a.title = required(b, "title", 120);
        a.body = required(b, "body", 2000);
        a.createdBy = optional(b, "createdBy", 120);
        a.priority = "urgent".equals(optional(b, "priority", 10)) ? "urgent" : "normal";
        a.createdAt = System.currentTimeMillis();
        news.save(a);
        return Map.of("ok", true, "id", a.id);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(HttpServletRequest req, @PathVariable Long id) {
        TeacherPortalAuth.requireAdmin(req);
        String school = TeacherPortalAuth.schoolId(req);
        news.findById(id).filter(a -> a.schoolId.equals(school)).ifPresent(news::delete);
        return Map.of("ok", true);
    }

    /* ───────── teacher -> admin / parents ───────── */
    @PostMapping("/teacher")
    @Transactional
    public Map<String, Object> send(HttpServletRequest req, @RequestBody ObjectNode b) {
        String school = TeacherPortalAuth.schoolId(req);
        String staff = TeacherPortalAuth.actingStaff(req, optional(b, "staffId", 64));
        String audience = optional(b, "audience", 20);
        if ("class".equals(audience)) audience = "parents";                  // older app versions
        if (!"admin".equals(audience) && !"parents".equals(audience)) throw bad("Send to admin or parents");
        String cls = optional(b, "className", 60);
        if ("parents".equals(audience) && (cls == null || cls.isBlank())) throw bad("Choose a class for parents");

        long now = System.currentTimeMillis();
        if (msgs.countByStaffIdAndSchoolIdAndCreatedAtAfter(staff, school, now - 3_600_000L) >= MAX_PER_HOUR)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages. Try again later.");

        TeacherMessage m = new TeacherMessage();
        m.schoolId = school; m.staffId = staff; m.staffName = optional(b, "staffName", 120);
        m.audience = audience; m.className = "parents".equals(audience) ? cls : null;
        m.title = required(b, "title", 120); m.body = required(b, "body", 1000);
        m.priority = "urgent".equals(optional(b, "priority", 10)) ? "urgent" : "normal";
        m.createdAt = now;
        msgs.save(m);
        return Map.of("ok", true, "id", m.id);
    }

    @GetMapping("/teacher-messages")
    public List<Map<String, Object>> inbox(HttpServletRequest req, @RequestParam(required = false) String audience,
                                           @RequestParam(required = false) String className) {
        String school = TeacherPortalAuth.schoolId(req);
        List<TeacherMessage> rows = (audience != null && className != null)
                ? msgs.findBySchoolIdAndAudienceAndClassNameOrderByCreatedAtDesc(school, audience, className, PageRequest.of(0, 100))
                : msgs.findBySchoolIdOrderByCreatedAtDesc(school, PageRequest.of(0, 200));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TeacherMessage m : rows) {
            if (TeacherPortalAuth.isTeacher(req) && !m.staffId.equals(TeacherPortalAuth.staffId(req))) continue;   // teachers only see their own
            if (audience != null && !audience.equals(m.audience)) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", m.id); o.put("from", m.staffName); o.put("staffId", m.staffId); o.put("audience", m.audience);
            o.put("className", m.className); o.put("title", m.title); o.put("body", m.body); o.put("priority", m.priority);
            o.put("date", java.time.Instant.ofEpochMilli(m.createdAt).toString()); o.put("read", m.readByAdmin);
            out.add(o);
        }
        return out;
    }

    @PostMapping("/teacher-messages/{id}/read")
    @Transactional
    public Map<String, Object> markRead(HttpServletRequest req, @PathVariable Long id) {
        TeacherPortalAuth.requireAdmin(req);
        String school = TeacherPortalAuth.schoolId(req);
        msgs.findById(id).filter(m -> m.schoolId.equals(school)).ifPresent(m -> { m.readByAdmin = true; msgs.save(m); });
        return Map.of("ok", true);
    }

    private static String optional(ObjectNode b, String f, int max) {
        if (!b.hasNonNull(f)) return null;
        String s = b.get(f).asText().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
    private static String required(ObjectNode b, String f, int max) {
        String s = optional(b, f, max);
        if (s == null || s.isBlank()) throw bad(f + " is required");
        return s;
    }
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
