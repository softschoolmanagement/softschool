package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Message;
import com.softschool.backend.model.Staff;
import com.softschool.backend.model.Student;
import com.softschool.backend.repository.MessageRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.repository.StudentRepository;
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
 * Messaging between the admin, teachers and parents.
 *
 * Channels (see Message): admin | class | parent_teacher | parent_admin.
 *
 *  TEACHER portal
 *    GET  /api/messages                          her own conversations, every channel
 *    POST /api/messages                          { channel:"admin" | "class" | "parent_teacher", className?, regNo?, body, clientId }
 *    POST /api/messages/read                     { channel, className?, regNo? }
 *
 *  ADMIN page (Announcements)
 *    GET  /api/messages/pulse                    { lastId, unread }     cheap probe, polled every few seconds
 *    GET  /api/messages/threads                  conversation list: one row per teacher / per student's parent
 *    GET  /api/messages?staffId=T-1[&since=ISO]  one teacher's chat      (channel admin)
 *    GET  /api/messages?regNo=R-5[&since=ISO]    one parent's chat       (channel parent_admin)
 *    POST /api/messages                          { staffId, body }  -> reply / start a chat with any teacher
 *                                                { regNo,   body }  -> reply / start a chat with any student's parent
 *    POST /api/messages/read                     { staffId } or { regNo }
 *
 * Privacy: the admin only sees the "admin" and "parent_admin" channels. A teacher's private chat with a
 * parent (parent_teacher) and her class broadcasts (class) are not exposed to the admin through this API.
 *
 * PARENT PORTAL (later): a parent will post with senderType PARENT into parent_admin (to the admin) or
 * parent_teacher (to one teacher), carrying the child's regNo. Add that principal type in send(); the admin
 * page and the teacher portal already display those messages with the student and father/guardian name.
 *
 * The tenant boundary (schoolId) always comes from the signed token.
 */
@RestController
@RequestMapping("/api/messages")
@CrossOrigin(origins = "*")
public class MessageController {

    private static final int MAX_LIST = 500;
    private static final int MAX_THREAD_SCAN = 3000;
    private static final int TEACHER_DAILY_LIMIT = 120;
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final List<String> ADMIN_CHANNELS = Arrays.asList(Message.CH_ADMIN, Message.CH_PARENT_ADMIN);

    @Autowired private MessageRepository messageRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolSessionService sessionService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ───────────── READ ─────────────
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String schoolId,
                                  @RequestParam(required = false) String staffId,
                                  @RequestParam(required = false) String regNo,
                                  @RequestParam(required = false) String channel,
                                  @RequestParam(required = false) String since,
                                  HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (schoolId != null && !schoolId.isBlank() && !schoolId.trim().equals(p.getSchoolId())) {
            return error(HttpStatus.FORBIDDEN, "The requested school does not match the authenticated school.");
        }
        Instant after = null;
        if (since != null && !since.isBlank()) {
            try { after = Instant.parse(since.trim()); } catch (Exception e) { return error(HttpStatus.BAD_REQUEST, "since must be an ISO-8601 time."); }
        }

        List<Message> rows;
        if (TeacherAccessGuard.isTeacher(p)) {
            rows = messageRepository.findBySchoolIdAndStaffIdOrderByCreatedAtDesc(p.getSchoolId(), staffIdOf(p), PageRequest.of(0, MAX_LIST));
        } else if (regNo != null && !regNo.isBlank()) {
            rows = messageRepository.findBySchoolIdAndChannelAndStudentRegNoOrderByCreatedAtDesc(
                    p.getSchoolId(), Message.CH_PARENT_ADMIN, regNo.trim(), PageRequest.of(0, MAX_LIST));
        } else if (staffId != null && !staffId.isBlank()) {
            rows = messageRepository.findBySchoolIdAndStaffIdAndChannelOrderByCreatedAtDesc(
                    p.getSchoolId(), staffId.trim(), Message.CH_ADMIN, PageRequest.of(0, MAX_LIST));
        } else {
            rows = messageRepository.findBySchoolIdAndChannelInOrderByCreatedAtDesc(p.getSchoolId(), ADMIN_CHANNELS, PageRequest.of(0, MAX_LIST));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : rows) {
            if (channel != null && !channel.isBlank() && !channel.equalsIgnoreCase(m.getChannel())) continue;
            if (after != null && !m.getCreatedAt().isAfter(after)) continue;
            out.add(view(m));
        }
        return ResponseEntity.ok(out);
    }

    /** Admin: one row per conversation, newest first, with unread counts. */
    @GetMapping("/threads")
    public ResponseEntity<?> threads(HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Admin only.");

        List<Message> rows = messageRepository.findBySchoolIdAndChannelInOrderByCreatedAtDesc(
                p.getSchoolId(), ADMIN_CHANNELS, PageRequest.of(0, MAX_THREAD_SCAN));
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        Map<String, String> teacherNames = new HashMap<>();
        for (Message m : rows) {                                  // newest first, so the first hit is the latest message
            boolean parent = Message.CH_PARENT_ADMIN.equals(m.getChannel());
            String key = parent ? "p:" + m.getStudentRegNo() : "t:" + m.getStaffId();
            Map<String, Object> t = byKey.get(key);
            if (t == null) {
                t = new LinkedHashMap<>();
                t.put("key", key);
                t.put("type", parent ? "parent" : "teacher");
                t.put("staffId", parent ? null : m.getStaffId());
                t.put("regNo", parent ? m.getStudentRegNo() : null);
                t.put("lastBody", m.getBody());
                t.put("lastSender", m.getSenderType());
                t.put("lastAt", m.getCreatedAt().toString());
                t.put("unread", 0);
                byKey.put(key, t);
            }
            if (!Message.FROM_ADMIN.equals(m.getSenderType()) && !m.isReadByAdmin()) t.put("unread", (int) t.get("unread") + 1);
            if (parent) {
                if (t.get("studentName") == null && m.getStudentName() != null) {
                    t.put("studentName", m.getStudentName());
                    t.put("guardianName", m.getGuardianName());
                    t.put("guardianRole", m.getGuardianRole());
                    t.put("className", m.getClassName());
                }
            } else if (Message.FROM_TEACHER.equals(m.getSenderType()) && m.getSenderName() != null) {
                teacherNames.putIfAbsent(m.getStaffId(), m.getSenderName());
            }
        }
        for (Map<String, Object> t : byKey.values()) {
            if ("teacher".equals(t.get("type"))) {
                String id = (String) t.get("staffId");
                String name = teacherNames.get(id);
                if (name == null) name = staffRepository.findByStaffIdAndSchoolId(id, p.getSchoolId()).map(Staff::getName).orElse(id);
                t.put("staffName", name);
            }
        }
        return ResponseEntity.ok(new ArrayList<>(byKey.values()));
    }

    /** Admin live view: poll this every few seconds; reload the lists only when lastId or unread change. */
    @GetMapping("/pulse")
    public ResponseEntity<?> pulse(HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Admin only.");
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("lastId", messageRepository.maxAdminVisibleId(p.getSchoolId()));
        o.put("unread", messageRepository.unreadForAdmin(p.getSchoolId()));
        return ResponseEntity.ok(o);
    }

    // ───────────── SEND ─────────────
    @PostMapping
    @Transactional
    public ResponseEntity<?> send(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        String text = text(body, "body", 1000);
        if (text.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Write a message first.");

        Message m = new Message();
        m.setSchoolId(p.getSchoolId());
        m.setBody(text);

        if (TeacherAccessGuard.isTeacher(p)) {
            String staffId = staffIdOf(p);
            Staff teacher = staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
            if (teacher == null || !"Teaching".equalsIgnoreCase(teacher.getType())) {
                return error(HttpStatus.FORBIDDEN, "This teacher account is no longer active.");
            }
            String channel = text(body, "channel", 20).toLowerCase();
            if (!Message.CH_ADMIN.equals(channel) && !Message.CH_CLASS.equals(channel) && !Message.CH_PARENT_TEACHER.equals(channel)) {
                return error(HttpStatus.BAD_REQUEST, "channel must be admin, class or parent_teacher.");
            }
            if (Message.CH_CLASS.equals(channel)) {
                String cls = text(body, "className", 100);
                if (!teachesClass(teacher, cls)) return error(HttpStatus.FORBIDDEN, "You can only message the parents of classes you teach.");
                m.setClassName(cls);
            }
            if (Message.CH_PARENT_TEACHER.equals(channel)) {
                Student st = studentRepository.findByRegNoAndSchoolId(text(body, "regNo", 64), p.getSchoolId()).orElse(null);
                if (st == null || (st.getStatus() != null && !"active".equalsIgnoreCase(st.getStatus()))) {
                    return error(HttpStatus.BAD_REQUEST, "That student was not found.");
                }
                String label = classLabel(st);
                if (!teachesClass(teacher, label)) return error(HttpStatus.FORBIDDEN, "You can only message the parents of students in your classes.");
                applyStudent(m, st);
                m.setClassName(label);
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
            m.setSenderType(Message.FROM_TEACHER);
            m.setSenderName(teacher.getName());
            m.setReadByTeacher(true);
        } else {
            // Admin: to any teacher (staffId) or to any student's parent (regNo)
            String name = text(body, "senderName", 80);
            m.setSenderType(Message.FROM_ADMIN);
            m.setSenderName(name.isEmpty() ? "Admin" : name);
            m.setReadByAdmin(true);
            String regNo = text(body, "regNo", 64);
            if (!regNo.isEmpty()) {
                Student st = studentRepository.findByRegNoAndSchoolId(regNo, p.getSchoolId()).orElse(null);
                if (st == null) return error(HttpStatus.BAD_REQUEST, "That student was not found in this school.");
                applyStudent(m, st);
                m.setClassName(classLabel(st));
                m.setStaffId("");
                m.setChannel(Message.CH_PARENT_ADMIN);
            } else {
                String staffId = text(body, "staffId", 64);
                Staff t = staffId.isEmpty() ? null : staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
                if (t == null) return error(HttpStatus.BAD_REQUEST, "Choose a teacher or a student's parent.");
                m.setStaffId(t.getStaffId());
                m.setChannel(Message.CH_ADMIN);
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ack(messageRepository.save(m)));
    }

    // ───────────── READ RECEIPTS ─────────────
    @PostMapping("/read")
    @Transactional
    public ResponseEntity<?> markRead(@RequestBody JsonNode body, HttpServletRequest req) {
        SchoolSessionService.Principal p = principal(req);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        int n;
        if (TeacherAccessGuard.isTeacher(p)) {
            String channel = text(body, "channel", 20).toLowerCase();
            String staffId = staffIdOf(p);
            if (Message.CH_CLASS.equals(channel)) {
                n = messageRepository.markClassChannelReadByTeacher(p.getSchoolId(), staffId, text(body, "className", 100));
            } else if (Message.CH_PARENT_TEACHER.equals(channel)) {
                n = messageRepository.markParentTeacherReadByTeacher(p.getSchoolId(), staffId, text(body, "regNo", 64));
            } else {
                n = messageRepository.markAdminChannelReadByTeacher(p.getSchoolId(), staffId);
            }
        } else {
            String regNo = text(body, "regNo", 64), staffId = text(body, "staffId", 64);
            if (!regNo.isEmpty()) n = messageRepository.markParentAdminReadByAdmin(p.getSchoolId(), regNo);
            else if (!staffId.isEmpty()) n = messageRepository.markAdminChannelReadByAdmin(p.getSchoolId(), staffId);
            else return error(HttpStatus.BAD_REQUEST, "staffId or regNo is required.");
        }
        return ResponseEntity.ok(Collections.singletonMap("updated", n));
    }

    // ───────────── helpers ─────────────
    private Map<String, Object> view(Message m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.getId());
        o.put("clientId", m.getClientId());
        o.put("staffId", m.getStaffId());
        o.put("channel", m.getChannel());
        o.put("className", m.getClassName());
        o.put("senderType", m.getSenderType());
        o.put("senderName", m.getSenderName());
        o.put("regNo", m.getStudentRegNo());
        o.put("studentName", m.getStudentName());
        o.put("guardianName", m.getGuardianName());
        o.put("guardianRole", m.getGuardianRole());
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
    private void applyStudent(Message m, Student st) {
        m.setStudentRegNo(st.getRegNo());
        m.setStudentName(st.getFullName());
        m.setGuardianName(st.getGuardianName());
        m.setGuardianRole(st.getGuardianRole());
    }
    private String classLabel(Student st) {
        String cls = st.getStudentClass() == null ? "" : st.getStudentClass().trim();
        String sec = st.getSection() == null ? "" : st.getSection().trim();
        return sec.isEmpty() ? cls : cls + " - " + sec;
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
