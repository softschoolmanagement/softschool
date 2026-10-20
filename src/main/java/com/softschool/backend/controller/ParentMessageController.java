package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.dto.ParentTeacherDTO;
import com.softschool.backend.model.Message;
import com.softschool.backend.model.ParentReadMark;
import com.softschool.backend.repository.MessageRepository;
import com.softschool.backend.repository.ParentMessageRepository;
import com.softschool.backend.repository.ParentReadMarkRepository;
import com.softschool.backend.repository.ParentStaffRepository;
import com.softschool.backend.service.ParentContext;
import com.softschool.backend.service.ParentUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Parent chat. It uses the SAME teacher_message table the admin page and the Teacher Portal already
 * read, so a parent's message shows up there with no change on their side (senderType PARENT, with the
 * student and father/guardian name filled in).
 *
 * Conversations of one child:
 *   thread "admin"        parent <-> school office      (channel parent_admin)
 *   thread "t:<staffId>"  parent <-> one teacher        (channel parent_teacher) + that teacher's
 *                         broadcasts to the whole class  (channel class, read-only for parents)
 *
 *   GET    /api/parent/messages?regNo=R[&since=ISO]    every message of that child, oldest first
 *   GET    /api/parent/messages/threads?regNo=R        one row per conversation, with unread counts
 *   POST   /api/parent/messages                        { regNo, channel:"parent_admin"|"parent_teacher", staffId?, body, clientId? }
 *   PUT    /api/parent/messages/{id}                   { body }   edit one of your own messages
 *   DELETE /api/parent/messages/{id}                   delete one of your own messages
 *   POST   /api/parent/messages/read                   { regNo, thread }   thread = "admin" | "t:<staffId>"
 *   GET    /api/parent/pulse                           { unread, byChild, lastId }  cheap probe: poll every few seconds
 *
 * There is no real push channel in this backend (no push-service credentials), so "notify the parent"
 * is the pulse endpoint: the portal polls it and shows the notification itself.
 */
@RestController
@RequestMapping("/api/parent")
public class ParentMessageController {

    private static final int MAX_LIST = 500;
    private static final int PARENT_DAILY_LIMIT = 60;
    private static final List<String> PRIVATE_CHANNELS = Arrays.asList(Message.CH_PARENT_ADMIN, Message.CH_PARENT_TEACHER);

    @Autowired private ParentContext parentContext;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ParentMessageRepository parentMessageRepository;
    @Autowired private ParentReadMarkRepository readMarkRepository;
    @Autowired private ParentStaffRepository staffRepository;

    // ───────────── READ ─────────────
    @GetMapping("/messages")
    public ResponseEntity<?> list(@RequestParam String regNo,
                                  @RequestParam(required = false) String since,
                                  HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");
        Instant after = null;
        if (since != null && !since.isBlank()) {
            try { after = Instant.parse(since.trim()); }
            catch (Exception e) { return ParentUtil.error(HttpStatus.BAD_REQUEST, "since must be an ISO-8601 time."); }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : loadAll(ctx, child)) {
            if (after != null && !m.getCreatedAt().isAfter(after)) continue;
            out.add(view(m));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/messages/threads")
    public ResponseEntity<?> threads(@RequestParam String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        Map<String, Instant> marks = readMarks(ctx);
        Map<String, Map<String, Object>> byThread = new LinkedHashMap<>();
        for (Message m : loadAll(ctx, child)) {                         // oldest first, so the last one seen is the latest
            String key = threadOf(m);
            Map<String, Object> t = byThread.get(key);
            if (t == null) {
                t = new LinkedHashMap<>();
                t.put("thread", key);
                t.put("type", "admin".equals(key) ? "admin" : "teacher");
                t.put("staffId", "admin".equals(key) ? null : m.getStaffId());
                t.put("staffName", null);
                t.put("unread", 0);
                byThread.put(key, t);
            }
            t.put("lastBody", m.getBody());
            t.put("lastFrom", fromOf(m));
            t.put("lastAt", m.getCreatedAt().toString());
            if (Message.FROM_TEACHER.equals(m.getSenderType()) && m.getSenderName() != null) t.put("staffName", m.getSenderName());
            Instant read = marks.get(regNo + "|" + key);
            if (!Message.FROM_PARENT.equals(m.getSenderType()) && (read == null || m.getCreatedAt().isAfter(read))) {
                t.put("unread", (int) t.get("unread") + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(byThread.values());
        out.sort((a, b) -> String.valueOf(b.get("lastAt")).compareTo(String.valueOf(a.get("lastAt"))));
        return ResponseEntity.ok(out);
    }

    /** Cheap probe for notifications: total unread across all children and the newest message id. */
    @GetMapping("/pulse")
    public ResponseEntity<?> pulse(HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        Map<String, Instant> marks = readMarks(ctx);
        long lastId = 0;
        int total = 0;
        Map<String, Integer> byChild = new LinkedHashMap<>();
        for (ParentChildDTO child : ctx.kids) {
            int unread = 0;
            for (Message m : loadAll(ctx, child)) {
                if (m.getId() != null && m.getId() > lastId) lastId = m.getId();
                Instant read = marks.get(child.getRegNo() + "|" + threadOf(m));
                if (!Message.FROM_PARENT.equals(m.getSenderType()) && (read == null || m.getCreatedAt().isAfter(read))) unread++;
            }
            byChild.put(child.getRegNo(), unread);
            total += unread;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("unread", total);
        o.put("byChild", byChild);
        o.put("lastId", lastId);
        return ResponseEntity.ok(o);
    }

    // ───────────── SEND ─────────────
    @PostMapping("/messages")
    @Transactional
    public ResponseEntity<?> send(@RequestBody JsonNode body, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(text(body, "regNo", 64));
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        String text = text(body, "body", 1000);
        if (text.isEmpty()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Write a message first.");
        String channel = text(body, "channel", 20).toLowerCase();
        if (!PRIVATE_CHANNELS.contains(channel)) return ParentUtil.error(HttpStatus.BAD_REQUEST, "channel must be parent_admin or parent_teacher.");

        String label = ParentUtil.classLabel(child);
        String staffId = "";
        if (Message.CH_PARENT_TEACHER.equals(channel)) {
            String wanted = text(body, "staffId", 64);
            ParentTeacherDTO teacher = null;
            for (ParentTeacherDTO t : staffRepository.findTeachersBySchoolId(ctx.schoolId)) {
                if (t.getStaffId() != null && t.getStaffId().equals(wanted)) { teacher = t; break; }
            }
            // The teacher must really teach this child's class — a parent cannot message any teacher in the school.
            if (teacher == null || !ParentUtil.teachesClass(teacher, label)) {
                return ParentUtil.error(HttpStatus.FORBIDDEN, "You can only message teachers of your child's class.");
            }
            staffId = teacher.getStaffId();
        }

        // The client's retry id is namespaced per child so two parents can never collide on the
        // (schoolId, staffId, clientId) unique key (staffId is "" for every parent_admin message).
        String clientId = null;
        String raw = text(body, "clientId", 40);
        if (!raw.isEmpty()) {
            clientId = "p" + sha256(child.getRegNo() + ":" + raw).substring(0, 30);
            Message dup = messageRepository.findBySchoolIdAndStaffIdAndClientId(ctx.schoolId, staffId, clientId).orElse(null);
            if (dup != null) return ResponseEntity.ok(ack(dup));              // retry of a message we already have
        }

        long today = parentMessageRepository.countBySchoolIdAndStudentRegNoAndSenderTypeAndCreatedAtAfter(
                ctx.schoolId, child.getRegNo(), Message.FROM_PARENT, Instant.now().minus(1, ChronoUnit.DAYS));
        if (today >= PARENT_DAILY_LIMIT) return ParentUtil.error(HttpStatus.TOO_MANY_REQUESTS, "Daily message limit reached. Try again tomorrow.");

        Message m = new Message();
        m.setSchoolId(ctx.schoolId);
        m.setStaffId(staffId);
        m.setChannel(channel);
        m.setClassName(label);
        m.setSenderType(Message.FROM_PARENT);
        String who = child.getGuardianName() == null || child.getGuardianName().isBlank() ? "Parent" : child.getGuardianName();
        m.setSenderName(ParentUtil.clip(who, 120));
        m.setStudentRegNo(child.getRegNo());                 // always from the verified child, never trusted from the client
        m.setStudentName(child.getFullName());
        m.setGuardianName(child.getGuardianName());
        m.setGuardianRole(child.getGuardianRole());
        m.setBody(text);
        m.setClientId(clientId);
        m.setReadByTeacher(false);
        m.setReadByAdmin(false);
        return ResponseEntity.status(HttpStatus.CREATED).body(ack(messageRepository.save(m)));
    }

    // ───────────── EDIT / DELETE (own messages only) ─────────────
    @PutMapping("/messages/{id}")
    @Transactional
    public ResponseEntity<?> edit(@PathVariable Long id, @RequestBody JsonNode body, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        String text = text(body, "body", 1000);
        if (text.isEmpty()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Write a message first.");
        Message m = ownMessage(ctx, id);
        if (m == null) return ParentUtil.error(HttpStatus.NOT_FOUND, "Message not found.");
        if (!text.equals(m.getBody())) {
            m.setBody(text);
            m.setEditedAt(Instant.now());
            messageRepository.save(m);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", true);
        o.put("id", m.getId());
        o.put("editedAt", m.getEditedAt() == null ? null : m.getEditedAt().toString());
        return ResponseEntity.ok(o);
    }

    @DeleteMapping("/messages/{id}")
    @Transactional
    public ResponseEntity<?> remove(@PathVariable Long id, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        Message m = ownMessage(ctx, id);
        if (m == null) return ParentUtil.error(HttpStatus.NOT_FOUND, "Message not found.");
        messageRepository.delete(m);
        return ResponseEntity.noContent().build();
    }

    // ───────────── READ RECEIPT ─────────────
    @PostMapping("/messages/read")
    @Transactional
    public ResponseEntity<?> markRead(@RequestBody JsonNode body, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(text(body, "regNo", 64));
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");
        String thread = text(body, "thread", 80);
        if (!"admin".equals(thread) && !(thread.startsWith("t:") && thread.length() > 2)) {
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "thread must be \"admin\" or \"t:<staffId>\".");
        }
        String key = child.getRegNo() + "|" + thread;
        ParentReadMark mark = readMarkRepository.findBySchoolIdAndPhoneAndThreadKey(ctx.schoolId, ctx.phone, key).orElseGet(() -> {
            ParentReadMark fresh = new ParentReadMark();
            fresh.setSchoolId(ctx.schoolId);
            fresh.setPhone(ctx.phone);
            fresh.setThreadKey(ParentUtil.clip(key, 140));
            return fresh;
        });
        mark.setReadAt(Instant.now());
        readMarkRepository.save(mark);

        return ResponseEntity.ok(Collections.singletonMap("ok", true));
    }

    // ───────────── helpers ─────────────

    /** Every message this child can see, oldest first. */
    private List<Message> loadAll(ParentContext.Ctx ctx, ParentChildDTO child) {
        Map<Long, Message> byId = new LinkedHashMap<>();
        for (Message m : parentMessageRepository.findBySchoolIdAndStudentRegNoAndChannelInOrderByCreatedAtDesc(
                ctx.schoolId, child.getRegNo(), PRIVATE_CHANNELS, PageRequest.of(0, MAX_LIST))) byId.put(m.getId(), m);

        String label = ParentUtil.classLabel(child);
        for (Message m : parentMessageRepository.findBySchoolIdAndChannelAndClassNameIgnoreCaseOrderByCreatedAtDesc(
                ctx.schoolId, Message.CH_CLASS, label, PageRequest.of(0, 200))) byId.put(m.getId(), m);
        String cls = child.getStudentClass() == null ? "" : child.getStudentClass().trim();
        if (!cls.isEmpty() && !cls.equalsIgnoreCase(label)) {       // broadcasts addressed to "Class 5" reach every section
            for (Message m : parentMessageRepository.findBySchoolIdAndChannelAndClassNameIgnoreCaseOrderByCreatedAtDesc(
                    ctx.schoolId, Message.CH_CLASS, cls, PageRequest.of(0, 200))) byId.put(m.getId(), m);
        }
        List<Message> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(Message::getCreatedAt).thenComparing(Message::getId));
        return all;
    }

    private Map<String, Instant> readMarks(ParentContext.Ctx ctx) {
        Map<String, Instant> m = new HashMap<>();
        for (ParentReadMark r : readMarkRepository.findBySchoolIdAndPhone(ctx.schoolId, ctx.phone)) m.put(r.getThreadKey(), r.getReadAt());
        return m;
    }

    /** A message the parent may edit/delete: their own, in a private channel, about one of THEIR children. */
    private Message ownMessage(ParentContext.Ctx ctx, Long id) {
        Message m = messageRepository.findByIdAndSchoolId(id, ctx.schoolId).orElse(null);
        if (m == null || !Message.FROM_PARENT.equals(m.getSenderType())) return null;
        if (!PRIVATE_CHANNELS.contains(m.getChannel())) return null;
        return ctx.child(m.getStudentRegNo()) == null ? null : m;
    }

    private static String threadOf(Message m) {
        return Message.CH_PARENT_ADMIN.equals(m.getChannel()) ? "admin" : "t:" + m.getStaffId();
    }

    private static String fromOf(Message m) {
        if (Message.FROM_PARENT.equals(m.getSenderType())) return "parent";
        if (Message.FROM_ADMIN.equals(m.getSenderType())) return "admin";
        return "teacher";
    }

    private Map<String, Object> view(Message m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.getId());
        o.put("thread", threadOf(m));
        o.put("channel", m.getChannel());
        o.put("from", fromOf(m));                          // parent | teacher | admin
        o.put("mine", Message.FROM_PARENT.equals(m.getSenderType()));
        o.put("broadcast", Message.CH_CLASS.equals(m.getChannel()));
        o.put("senderName", m.getSenderName());
        o.put("staffId", Message.CH_PARENT_ADMIN.equals(m.getChannel()) ? null : m.getStaffId());
        o.put("regNo", m.getStudentRegNo());
        o.put("body", m.getBody());
        o.put("createdAt", m.getCreatedAt().toString());    // ISO-8601 UTC
        o.put("editedAt", m.getEditedAt() == null ? null : m.getEditedAt().toString());
        // Delivery ticks for the parent's own messages: read once the teacher / admin opened the chat.
        boolean read = Message.CH_PARENT_ADMIN.equals(m.getChannel()) ? m.isReadByAdmin() : m.isReadByTeacher();
        o.put("read", Message.FROM_PARENT.equals(m.getSenderType()) && read);
        return o;
    }

    private Map<String, Object> ack(Message m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", true);
        o.put("id", m.getId());
        o.put("createdAt", m.getCreatedAt().toString());
        return o;
    }

    private String text(JsonNode n, String field, int max) {
        String v = n.path(field).asText("").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
