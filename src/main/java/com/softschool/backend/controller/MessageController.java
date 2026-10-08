package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Message;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.MessageRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.security.TeacherAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Teacher Portal messaging (conversations).
 *
 *   GET  /api/messages[?staffId=&channel=]     teacher: her own conversations | admin: whole school, or one teacher's
 *   POST /api/messages                         teacher: { channel:"admin"|"class", className?, body, clientId }
 *                                              admin:   { staffId, channel:"admin", body, senderName? }  (reply to a teacher)
 *   POST /api/messages/read                    teacher: { channel, className? } | admin: { staffId }
 *
 * "School notices" (admin -> all teachers) stay in AnnouncementController.
 *
 * PARENT PORTAL (later): parents will read and post into the "class" channel of
 * their child's class — add a PARENT principal check where marked below.
 * The tenant boundary (schoolId) always comes from the signed token.
 */
@RestController
@RequestMapping("/api/messages")
@CrossOrigin(origins = "*")
public class MessageController {

    private static final int MAX_LIST = 500;
    private static final int TEACHER_DAILY_LIMIT = 120;
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    @Autowired private MessageRepository messageRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private SchoolSessionService sessionService;

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String schoolId,
                                  @RequestParam(required = false) String staffId,
                                  @RequestParam(required = false) String channel,
                                  HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (schoolId != null && !schoolId.isBlank() && !schoolId.trim().equals(p.getSchoolId())) {
            return error(HttpStatus.FORBIDDEN, "The requested school does not match the authenticated school.");
        }

        List<Message> rows;
        if (TeacherAccessGuard.isTeacher(p)) {
            rows = messageRepository.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(p.getSchoolId(), staffIdOf(p), PageRequest.of(0, MAX_LIST));
        } else if (staffId != null && !staffId.isBlank()) {
            rows = messageRepository.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(p.getSchoolId(), staffId.trim(), PageRequest.of(0, MAX_LIST));
        } else {
            rows = messageRepository.findBySchoolIdOrderByCreatedAtDesc(p.getSchoolId(), PageRequest.of(0, MAX_LIST));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : rows) {
            if (channel != null && !channel.isBlank() && !channel.equalsIgnoreCase(m.getChannel())) continue;
            out.add(view(m));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> send(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        String text = text(body, "body", 1000);
        if (text.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Write a message first.");

        boolean teacherCaller = TeacherAccessGuard.isTeacher(p);
        String staffId;
        Staff teacher;
        Message m = new Message();
        m.setSchoolId(p.getSchoolId());
        m.setBody(text);

        if (teacherCaller) {
            staffId = staffIdOf(p);
            teacher = staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
            if (teacher == null || !"Teaching".equalsIgnoreCase(teacher.getType())) {
                return error(HttpStatus.FORBIDDEN, "This teacher account is no longer active.");
            }
            String channel = text(body, "channel", 10).toLowerCase();
            if (!Message.CH_ADMIN.equals(channel) && !Message.CH_CLASS.equals(channel)) {
                return error(HttpStatus.BAD_REQUEST, "channel must be admin or class.");
            }
            String cls = text(body, "className", 100);
            if (Message.CH_CLASS.equals(channel) && !teachesClass(teacher, cls)) {
                return error(HttpStatus.FORBIDDEN, "You can only message the parents of classes you teach.");
            }
            String clientId = text(body, "clientId", 40);
            if (!clientId.isEmpty()) {
                if (!CLIENT_ID.matcher(clientId).matches()) return error(HttpStatus.BAD_REQUEST, "Invalid clientId.");
                Message dup = messageRepository.findBySchoolIdAndStaffIdAndClientId(p.getSchoolId(), staffId, clientId).orElse(null);
                if (dup != null) return ResponseEntity.ok(ack(dup));              // retry of a message we already have
                m.setClientId(clientId);
            }
            long today = messageRepository.countBySchoolIdAndStaffIdAndSenderTypeAndCreatedAtAfter(
                    p.getSchoolId(), staffId, Message.FROM_TEACHER, Instant.now().minus(1, ChronoUnit.DAYS));
            if (today >= TEACHER_DAILY_LIMIT) return error(HttpStatus.TOO_MANY_REQUESTS, "Daily message limit reached. Try again tomorrow.");

            m.setStaffId(staffId);
            m.setChannel(channel);
            m.setClassName(Message.CH_CLASS.equals(channel) ? cls : null);
            m.setSenderType(Message.FROM_TEACHER);
            m.setSenderName(teacher.getName());
            m.setReadByTeacher(true);
        } else {
            // Admin replying to one teacher.
            staffId = text(body, "staffId", 64);
            teacher = staffId.isEmpty() ? null : staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
            if (teacher == null) return error(HttpStatus.BAD_REQUEST, "That teacher was not found in this school.");
            m.setStaffId(teacher.getStaffId());
            m.setChannel(Message.CH_ADMIN);
            m.setSenderType(Message.FROM_ADMIN);
            String name = text(body, "senderName", 80);
            m.setSenderName(name.isEmpty() ? "Admin" : name);
            m.setReadByAdmin(true);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ack(messageRepository.save(m)));
    }

    @PostMapping("/read")
    @Transactional
    public ResponseEntity<?> markRead(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        int n;
        if (TeacherAccessGuard.isTeacher(p)) {
            String channel = text(body, "channel", 10).toLowerCase();
            if (Message.CH_CLASS.equals(channel)) {
                n = messageRepository.markClassChannelReadByTeacher(p.getSchoolId(), staffIdOf(p), text(body, "className", 100));
            } else {
                n = messageRepository.markAdminChannelReadByTeacher(p.getSchoolId(), staffIdOf(p));
            }
        } else {
            String staffId = text(body, "staffId", 64);
            if (staffId.isEmpty()) return error(HttpStatus.BAD_REQUEST, "staffId is required.");
            n = messageRepository.markAdminChannelReadByAdmin(p.getSchoolId(), staffId);
        }
        return ResponseEntity.ok(Collections.singletonMap("updated", n));
    }

    // ── helpers ──
    private Map<String, Object> view(Message m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.getId());
        o.put("clientId", m.getClientId());
        o.put("staffId", m.getStaffId());
        o.put("channel", m.getChannel());
        o.put("className", m.getClassName());
        o.put("senderType", m.getSenderType());
        o.put("senderName", m.getSenderName());
        o.put("body", m.getBody());
        o.put("createdAt", m.getCreatedAt().toString());   // ISO-8601 UTC
        o.put("readByTeacher", m.isReadByTeacher());
        o.put("readByAdmin", m.isReadByAdmin());
        return o;
    }
    private Map<String, Object> ack(Message m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", true); o.put("id", m.getId()); o.put("createdAt", m.getCreatedAt().toString());
        return o;
    }

    /** True when the class label (e.g. "Class 5 - A") is one the teacher teaches or is incharge of. */
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
    private String text(JsonNode n, String field, int max) {
        String v = n.path(field).asText("").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
