package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.softschool.backend.dto.ParentAttendanceDTO;
import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.dto.ParentTeacherDTO;
import com.softschool.backend.model.*;
import com.softschool.backend.repository.*;
import com.softschool.backend.service.DataCacheService;
import com.softschool.backend.service.DataCacheService.Dep;
import com.softschool.backend.service.FileStorageService;
import com.softschool.backend.service.ParentChildrenService;
import com.softschool.backend.service.ParentContext;
import com.softschool.backend.service.ParentUtil;
import com.softschool.backend.service.StudentCacheService;
import com.softschool.backend.service.StudentCacheService.CachedFile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Read-only data for the parent portal pages. Every route is scoped to ONE of the parent's own
 * children ({regNo} is checked against ParentContext.Ctx#child, which only knows this phone's
 * children) and the school always comes from the signed token.
 *
 *   GET /api/parent/children                                   the parent's children
 *   GET /api/parent/children/{regNo}/photo                     the child's photo (bytes)
 *   GET /api/parent/children/{regNo}/attendance[?month=YYYY-MM]
 *   GET /api/parent/children/{regNo}/fees
 *   GET /api/parent/children/{regNo}/diary
 *   GET /api/parent/children/{regNo}/diary/{id}/image/{n}
 *   GET /api/parent/children/{regNo}/announcements
 *   GET /api/parent/children/{regNo}/tests
 *   GET /api/parent/children/{regNo}/timetable
 *   GET /api/parent/children/{regNo}/results
 *   GET /api/parent/children/{regNo}/teachers
 *
 * Chat is in ParentMessageController, applications/complaints/feedback in ParentSubmissionController.
 *
 * PERFORMANCE: authorisation ALWAYS runs first (ctx + child checks) and is never cached; only the data
 * built for an already-authorised child is cached, per school and child, in DataCacheService. Each entry
 * names the kinds of rows it depends on, so it is dropped as soon as the school changes any of them.
 */
@RestController
@RequestMapping("/api/parent")
public class ParentPortalController {

    private static final int MAX_LIST = 200;
    /** Safety-net lifetime of a cached view; real freshness comes from the invalidation listeners. */
    private static final long TTL = 60;

    @Autowired private ParentContext parentContext;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private FinanceRepository financeRepository;
    @Autowired private SchoolSettingsRepository settingsRepository;
    @Autowired private DiaryEntryRepository diaryRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private ParentTestRepository testRepository;
    @Autowired private ClassTimetableRepository timetableRepository;
    @Autowired private ExamResultRepository resultRepository;
    @Autowired private ParentStaffRepository staffRepository;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private DataCacheService cache;
    @Autowired private StudentCacheService studentCache;

    private final ObjectMapper mapper = new ObjectMapper();

    // ───────────── SCHOOL BRANDING ─────────────
    /**
     * Public-facing identity for the school attached to this signed parent session.
     * This also works for a first-time parent who must change the activation password:
     * only the school name and logo are returned, never account or student data.
     */
    @GetMapping("/school")
    public ResponseEntity<?> school(HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolveForPasswordChange(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        School school = schoolRepository.findBySchoolId(ctx.schoolId).orElse(null);
        if (school == null) return ParentUtil.error(HttpStatus.NOT_FOUND, "School not found.");

        SchoolSettings settings = settingsRepository.findBySchoolId(ctx.schoolId).orElse(null);
        String settingsName = settings == null ? null : settings.getSchoolName();
        String name = settingsName != null && !settingsName.isBlank()
                ? settingsName.trim()
                : school.getName();

        Map<String, Object> branding = new LinkedHashMap<>();
        branding.put("schoolId", ctx.schoolId);
        branding.put("name", name);
        branding.put("logo", school.getLogo());
        return ResponseEntity.ok(branding);
    }

    // ───────────── CHILDREN ─────────────
    @GetMapping("/children")
    public ResponseEntity<?> children(HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParentChildDTO c : ctx.kids) out.add(ParentChildrenService.view(c));
        return ResponseEntity.ok(out);
    }

    /**
     * The child's photo. PERFORMANCE: served from the same in-memory file cache the admin pages use
     * (StudentCacheService), so a page load no longer loads the child's whole student row (every
     * LONGTEXT column included) from MySQL and re-decodes it. The entry is dropped automatically when
     * that student's row changes.
     */
    @GetMapping("/children/{regNo}/photo")
    public ResponseEntity<?> photo(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (ctx.child(regNo) == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        final String schoolId = ctx.schoolId;
        CachedFile file;
        try {
            file = studentCache.getFile(schoolId, "photo", regNo, () -> loadPhoto(schoolId, regNo));
        } catch (UncheckedIOException e) {
            return ResponseEntity.notFound().build();
        }
        if (file == null || file.mediaType() == null || !file.mediaType().startsWith("image/")) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().header("Content-Type", file.mediaType())
                .header("Cache-Control", "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }

    /** Reads and decodes the stored photo; null when there is nothing usable (never cached). */
    private CachedFile loadPhoto(String schoolId, String regNo) {
        Student s = studentRepository.findByRegNoAndSchoolId(regNo, schoolId).orElse(null);
        String stored = s == null ? null : s.getPhoto();
        if (stored == null || stored.isBlank()) return null;
        try {
            byte[] bytes;
            if (fileStorageService.isManagedPath(stored)) {
                Path f = fileStorageService.resolve(stored);
                if (!Files.exists(f)) return null;
                bytes = Files.readAllBytes(f);
            } else {
                // Older rows hold the picture inline, as bare base64 or a data: URI.
                String b64 = stored.trim();
                int mark = b64.indexOf("base64,");
                if (b64.startsWith("data:") && mark >= 0) b64 = b64.substring(mark + 7);
                bytes = Base64.getMimeDecoder().decode(b64);
            }
            String type = FileStorageService.detectMediaType(bytes);
            return type == null ? null : new CachedFile(bytes, type);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    // ───────────── ATTENDANCE ─────────────
    /**
     * { days: { "2026-10-05": "absent", ... }, details: { "2026-10-05": {checkIn, checkOut, reason} },
     *   summary: { present, absent, leave, total }, month }
     * Statuses are present | absent | leave | holiday. The school does not store holidays, so the
     * portal keeps drawing Sundays itself.
     */
    @GetMapping("/children/{regNo}/attendance")
    public ResponseEntity<?> attendance(@PathVariable String regNo,
                                        @RequestParam(required = false) String month,
                                        HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (ctx.child(regNo) == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        YearMonth parsed = null;
        if (month != null && !month.isBlank()) {
            try { parsed = YearMonth.parse(month.trim()); }
            catch (Exception e) { return ParentUtil.error(HttpStatus.BAD_REQUEST, "month must look like 2026-10."); }
        }
        final YearMonth ym = parsed;
        final LocalDate today = LocalDate.now();
        final LocalDate from = ym != null ? ym.atDay(1) : today.minusDays(150);
        final LocalDate to = ym != null ? ym.atEndOfMonth() : today;
        // The rolling "last 150 days" window moves at midnight, so today's date is part of that key.
        String key = "att|" + regNo + "|" + (ym == null ? "recent:" + today : ym.toString());

        Map<String, Object> out = cache.get(ctx.schoolId, key, TTL,
                () -> buildAttendance(ctx.schoolId, regNo, ym, from, to), Dep.ATTENDANCE);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> buildAttendance(String schoolId, String regNo, YearMonth ym,
                                                LocalDate from, LocalDate to) {
        Map<String, String> days = new TreeMap<>();
        Map<String, Object> details = new TreeMap<>();
        int present = 0, absent = 0, leave = 0;
        // Only the six plain columns, only inside the window (idx_att_member) - no capturedPhoto LONGTEXT.
        for (ParentAttendanceDTO a : attendanceRepository.findParentView(schoolId, regNo, from, to)) {
            if (!"STUDENT".equalsIgnoreCase(a.getMemberType()) || a.getDate() == null) continue;
            String st = ParentUtil.normalizeAttendance(a.getStatus());
            String key = a.getDate().toString();
            days.put(key, st);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("checkIn", a.getCheckIn() == null ? null : a.getCheckIn().toString());
            d.put("checkOut", a.getCheckOut() == null ? null : a.getCheckOut().toString());
            d.put("reason", a.getReason());
            details.put(key, d);
            if ("present".equals(st)) present++; else if ("absent".equals(st)) absent++; else if ("leave".equals(st)) leave++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("present", present);
        summary.put("absent", absent);
        summary.put("leave", leave);
        summary.put("total", present + absent + leave);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym == null ? null : ym.toString());
        out.put("days", days);
        out.put("details", details);
        out.put("summary", summary);
        return out;
    }

    // ───────────── FEES ─────────────
    /**
     * One entry per month the school actually billed (a fee row only exists after a voucher was
     * generated or a payment / fine was recorded — merely looking at a student creates nothing),
     * newest first. Fields follow what fees.js shows: challan, monthLabel, issueDate, dueDate, tuition,
     * totalWithinDue, totalAfterDue, paidAmount, status (paid | due | overdue).
     *
     * The school keeps no voucher number, so challan is "<regNo>/<yyyy-MM>". Online payment is not
     * part of this API: the portal only shows what is owed.
     */
    @GetMapping("/children/{regNo}/fees")
    public ResponseEntity<?> fees(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (ctx.child(regNo) == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        // "due" turns into "overdue" as days pass, so today's date is part of the key.
        String key = "fees|" + regNo + "|" + LocalDate.now();
        Map<String, Object> out = cache.get(ctx.schoolId, key, TTL,
                () -> buildFees(ctx.schoolId, regNo), Dep.FINANCE, Dep.SETTINGS);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> buildFees(String schoolId, String regNo) {
        SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
        int deadlineDay = ss == null || ss.getLateFeeDeadlineDay() == null ? 10 : ss.getLateFeeDeadlineDay();
        int grace = ss == null || ss.getLateFeeGrace() == null ? 0 : ss.getLateFeeGrace();
        boolean lateOn = ss != null && !Boolean.FALSE.equals(ss.getLateFeeEnabled());
        boolean percent = ss != null && "percent".equalsIgnoreCase(ss.getLateFeeType());
        double lateAmount = ss == null || ss.getLateFeeAmount() == null ? 0.0 : ss.getLateFeeAmount();

        List<Finance> rows = new ArrayList<>();
        for (Finance f : financeRepository.findBySchoolIdAndRegNo(schoolId, regNo)) {
            if (Finance.TYPE_STUDENT_FEE.equals(f.getRecordType()) && f.getMonthKey() != null
                    && f.getMonthKey().matches("\\d{4}-\\d{2}")) rows.add(f);
        }
        rows.sort((a, b) -> b.getMonthKey().compareTo(a.getMonthKey()));

        LocalDate today = LocalDate.now();
        double outstanding = 0.0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Finance f : rows) {
            YearMonth ym = YearMonth.parse(f.getMonthKey());
            LocalDate due = ym.atDay(Math.min(Math.max(deadlineDay, 1), ym.lengthOfMonth()));
            double net = f.getNetPayable() == null ? 0.0 : f.getNetPayable();
            double paid = f.getPaidAmount() == null ? 0.0 : f.getPaidAmount();
            double remaining = f.getRemainingBalance() == null ? Math.max(0.0, net - paid) : f.getRemainingBalance();
            boolean isPaid = remaining <= 0.01;
            double late = (!isPaid && lateOn) ? (percent ? net * lateAmount / 100.0 : lateAmount) : 0.0;

            String status = isPaid ? "paid" : (today.isAfter(due.plusDays(grace)) ? "overdue" : "due");
            if (!isPaid) outstanding += remaining;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("challan", regNo + "/" + f.getMonthKey());
            m.put("monthKey", f.getMonthKey());
            m.put("monthLabel", ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
            m.put("issueDate", ym.atDay(1).toString());
            m.put("dueDate", due.toString());
            m.put("tuition", ParentUtil.round2(f.getBaseTuitionFee()));
            m.put("transport", ParentUtil.round2(f.getTransportFee()));
            m.put("other", ParentUtil.round2(f.getOtherCharges()));      // includes rolled-over arrears
            m.put("fine", ParentUtil.round2(f.getTotalFineCharged()));
            m.put("discount", ParentUtil.round2(f.getTotalDiscountApplied()));
            m.put("totalWithinDue", ParentUtil.round2(net));
            m.put("totalAfterDue", ParentUtil.round2(net + late));
            m.put("paidAmount", ParentUtil.round2(paid));
            m.put("remaining", ParentUtil.round2(remaining));
            m.put("status", status);
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outstanding", ParentUtil.round2(outstanding));
        out.put("fees", list);
        return out;
    }

    // ───────────── DIARY ─────────────
    @GetMapping("/children/{regNo}/diary")
    public ResponseEntity<?> diary(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        List<Map<String, Object>> out = cache.get(ctx.schoolId, "diary|" + regNo, TTL,
                () -> buildDiary(ctx.schoolId, regNo, child), Dep.DIARY, Dep.STUDENTS);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> buildDiary(String schoolId, String regNo, ParentChildDTO child) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiaryEntry d : diaryRepository.findBySchoolIdAndClassNameAndPublishedTrueOrderByCreatedAtDesc(
                schoolId, ParentUtil.classLabel(child), PageRequest.of(0, MAX_LIST))) {
            int count = d.getImagePaths() == null || d.getImagePaths().isBlank() ? 0 : d.getImagePaths().split("\n").length;
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < count; i++) urls.add("/api/parent/children/" + regNo + "/diary/" + d.getId() + "/image/" + i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("subject", d.getSubject());
            m.put("note", d.getNote());
            m.put("date", d.getDiaryDate());
            m.put("teacher", d.getStaffName());
            m.put("imageCount", count);
            m.put("imageUrls", urls);      // fetch each with the Authorization header and show as a blob
            m.put("createdAt", d.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/children/{regNo}/diary/{id}/image/{n}")
    public ResponseEntity<?> diaryImage(@PathVariable String regNo, @PathVariable Long id, @PathVariable int n,
                                        HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");
        DiaryEntry d = diaryRepository.findByIdAndSchoolId(id, ctx.schoolId).orElse(null);
        // The diary must be published AND belong to this child's own class.
        if (d == null || !d.isPublished() || !ParentUtil.classLabel(child).equalsIgnoreCase(d.getClassName())) {
            return ResponseEntity.notFound().build();
        }
        String[] paths = d.getImagePaths().split("\n");
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

    // ───────────── ANNOUNCEMENTS ─────────────
    /** Notices posted to this child's class (by the admin or a teacher). */
    @GetMapping("/children/{regNo}/announcements")
    public ResponseEntity<?> announcements(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        List<Map<String, Object>> out = cache.get(ctx.schoolId, "ann|" + regNo, TTL,
                () -> buildAnnouncements(ctx.schoolId, child), Dep.ANNOUNCEMENTS, Dep.STUDENTS);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> buildAnnouncements(String schoolId, ParentChildDTO child) {
        Instant fresh = Instant.now().minus(3, ChronoUnit.DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Announcement a : announcementRepository.findBySchoolIdAndAudienceOrderByCreatedAtDesc(
                schoolId, Announcement.AUD_CLASS, PageRequest.of(0, MAX_LIST))) {
            if (!ParentUtil.classMatches(a.getClassName(), child)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("body", a.getBody());
            m.put("from", a.getSenderName() == null || a.getSenderName().isBlank() ? "Admin" : a.getSenderName());
            m.put("senderType", a.getSenderType());
            m.put("className", a.getClassName());
            m.put("priority", a.getPriority());
            m.put("date", a.getCreatedAt().toString());       // ISO-8601 UTC
            m.put("isNew", a.getCreatedAt().isAfter(fresh));
            out.add(m);
        }
        return out;
    }

    // ───────────── TESTS ─────────────
    /**
     * Tests of the child's class, with THIS child's own mark only (never another child's, never the
     * questions or answers). Assumes teacher-portal results are keyed by regNo: results[regNo].score.
     */
    @GetMapping("/children/{regNo}/tests")
    public ResponseEntity<?> tests(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        List<Map<String, Object>> out = cache.get(ctx.schoolId, "tests|" + regNo, TTL,
                () -> buildTests(ctx.schoolId, regNo, child), Dep.TESTS, Dep.STUDENTS);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> buildTests(String schoolId, String regNo, ParentChildDTO child) {
        String cls = child.getStudentClass() == null ? "" : child.getStudentClass().trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TeacherTest t : testRepository.findForClass(schoolId, ParentUtil.classLabel(child), cls, PageRequest.of(0, 150))) {
            JsonNode p;
            try { p = mapper.readTree(t.getPayloadJson()); } catch (Exception e) { continue; }
            if (!p.isObject()) continue;

            String date = "";
            for (String key : new String[]{"date", "testDate", "heldOn"}) {
                String v = p.path(key).asText("");
                if (!v.isBlank()) { date = v; break; }
            }
            if (date.isBlank()) date = t.getUpdatedAt().toString().substring(0, 10);

            double total = p.path("totalMarks").asDouble(0);
            JsonNode mine = p.path("results").path(regNo);
            boolean marked = mine.isObject() && mine.has("score") && !mine.path("score").isNull();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getTestId());
            m.put("title", t.getTitle());
            m.put("subject", t.getSubject());
            m.put("kind", t.getType());                       // "quiz" | "test"
            m.put("date", date);
            m.put("totalMarks", total > 0 ? total : null);
            m.put("score", marked ? mine.path("score").asDouble() : null);
            m.put("percentage", marked && total > 0 ? ParentUtil.round2(mine.path("score").asDouble() * 100.0 / total) : null);
            m.put("status", marked ? "marked" : "pending");
            out.add(m);
        }
        return out;
    }

    // ───────────── TIMETABLE ─────────────
    /** { available, className, periods:[...], days:{...} } — stored as the school sent it. */
    @GetMapping("/children/{regNo}/timetable")
    public ResponseEntity<?> timetable(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        ObjectNode out = cache.get(ctx.schoolId, "tt|" + regNo, TTL,
                () -> buildTimetable(ctx.schoolId, child), Dep.TIMETABLE, Dep.STUDENTS);
        return ResponseEntity.ok(out);
    }

    private ObjectNode buildTimetable(String schoolId, ParentChildDTO child) {
        ClassTimetable tt = timetableRepository.findBySchoolIdAndClassNameIgnoreCase(schoolId, ParentUtil.classLabel(child)).orElse(null);
        if (tt == null && child.getStudentClass() != null && !child.getStudentClass().isBlank()) {
            tt = timetableRepository.findBySchoolIdAndClassNameIgnoreCase(schoolId, child.getStudentClass().trim()).orElse(null);
        }
        ObjectNode out = mapper.createObjectNode();
        if (tt == null) {
            out.put("available", false);
            return out;
        }
        try {
            JsonNode p = mapper.readTree(tt.getPayloadJson());
            if (p.isObject()) out.setAll((ObjectNode) p);
        } catch (Exception ignored) { /* serve an empty timetable rather than fail */ }
        out.put("available", true);
        out.put("className", tt.getClassName());
        out.put("updatedAt", tt.getUpdatedAt().toString());
        return out;
    }

    // ───────────── RESULTS ─────────────
    /** Same shape result.js already uses: { "2025-2026": { "first-term": { subjects:[{name,total,obtained}] } } }. */
    @GetMapping("/children/{regNo}/results")
    public ResponseEntity<?> results(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (ctx.child(regNo) == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        ObjectNode out = cache.get(ctx.schoolId, "res|" + regNo, TTL,
                () -> buildResults(ctx.schoolId, regNo), Dep.RESULTS);
        return ResponseEntity.ok(out);
    }

    private ObjectNode buildResults(String schoolId, String regNo) {
        ObjectNode out = mapper.createObjectNode();
        for (ExamResult r : resultRepository.findBySchoolIdAndRegNoAndVisibleTrue(schoolId, regNo)) {
            try {
                ObjectNode session = out.has(r.getSession()) ? (ObjectNode) out.get(r.getSession()) : out.putObject(r.getSession());
                session.set(r.getTerm(), mapper.readTree(r.getPayloadJson()));
            } catch (Exception ignored) { /* skip a corrupt row */ }
        }
        return out;
    }

    // ───────────── TEACHERS (who can this parent message?) ─────────────
    @GetMapping("/children/{regNo}/teachers")
    public ResponseEntity<?> teachers(@PathVariable String regNo, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        ParentChildDTO child = ctx.child(regNo);
        if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");

        List<Map<String, Object>> out = cache.get(ctx.schoolId, "teachers|" + regNo, TTL,
                () -> buildTeachers(ctx.schoolId, child), Dep.STAFF, Dep.STUDENTS);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> buildTeachers(String schoolId, ParentChildDTO child) {
        String label = ParentUtil.classLabel(child);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParentTeacherDTO t : staffRepository.findTeachersBySchoolId(schoolId)) {
            if (!ParentUtil.teachesClass(t, label)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("staffId", t.getStaffId());
            m.put("name", t.getName());
            m.put("subject", t.getSubjects());
            out.add(m);
        }
        return out;
    }
}
