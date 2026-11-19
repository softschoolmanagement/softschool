package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.Finance;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Student;
import com.softschool.backend.model.WhatsAppConfig;
import com.softschool.backend.model.WhatsAppLog;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.repository.FinanceRepository;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.repository.StudentRepository;
import com.softschool.backend.repository.WhatsAppConfigRepository;
import com.softschool.backend.repository.WhatsAppLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Baileys gateway (whatsapp-service/). Every school links ITS OWN WhatsApp number, so a
 * school's parents always hear from that school's number. All sending is fire-and-forget on a
 * background thread: a WhatsApp problem must never make saving attendance or a fee payment fail.
 */
@Service
public class WhatsAppService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);

    @Value("${whatsapp.service-url:http://127.0.0.1:3001}") private String serviceUrl;
    @Value("${whatsapp.api-key:}") private String apiKey;
    @Value("${whatsapp.default-country-code:92}") private String countryCode;

    @Autowired private WhatsAppConfigRepository configRepo;
    @Autowired private WhatsAppLogRepository logRepo;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolSettingsRepository settingsRepository;
    @Autowired private FinanceRepository financeRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private PlanEnforcementService planEnforcement;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "whatsapp-notify");
        t.setDaemon(true);
        return t;
    });
    private volatile RestClient client;

    private RestClient http() {
        if (client == null) {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(3000);
            f.setReadTimeout(20000);
            client = RestClient.builder().requestFactory(f).baseUrl(serviceUrl)
                    .defaultHeader("x-api-key", apiKey).build();
        }
        return client;
    }

    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    /**
     * True when the super admin banned WhatsApp for this school (directly or via its plan).
     * Every outgoing path below checks this, so a ban stops automatic alerts, fee reminders,
     * broadcasts and manual sends alike - not just the Settings screen.
     */
    public boolean isBanned(String schoolId) { return planEnforcement.isWhatsAppBanned(schoolId); }

    /** Unlinks the school's WhatsApp number in the background (used when a ban is switched on). Never throws. */
    public void disconnectQuietly(String schoolId) {
        if (!isConfigured() || schoolId == null) return;
        pool.submit(() -> {
            try { logout(schoolId); }
            catch (Exception ignored) { /* nothing was linked, or the gateway is down */ }
        });
    }

    // ─────────────── config ───────────────
    public WhatsAppConfig config(String schoolId) {
        return configRepo.findBySchoolId(schoolId).orElseGet(() -> {
            WhatsAppConfig c = new WhatsAppConfig();
            c.setSchoolId(schoolId);
            return configRepo.save(c);
        });
    }

    // ─────────────── session control (used by the Settings tab) ───────────────
    @SuppressWarnings("unchecked")
    public Map<String, Object> status(String schoolId) {
        if (isBanned(schoolId)) return Map.of("state", "banned", "connected", false, "banned", true);
        if (!isConfigured()) return Map.of("state", "not_configured", "connected", false);
        try {
            return http().get().uri("/sessions/{id}/status", schoolId).retrieve().body(Map.class);
        } catch (Exception e) {
            return Map.of("state", "service_down", "connected", false);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> connect(String schoolId, String phone) {
        Map<String, Object> body = new HashMap<>();
        if (phone != null && !phone.isBlank()) { body.put("phone", phone); body.put("country", countryCode); }
        return http().post().uri("/sessions/{id}/connect", schoolId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    public void logout(String schoolId) {
        http().post().uri("/sessions/{id}/logout", schoolId).retrieve().toBodilessEntity();
    }

    /** Tells the gateway this school's own daily limit right away (it is also sent along with every batch). */
    public void pushLimit(String schoolId, Integer dailyLimit) {
        if (!isConfigured()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("dailyLimit", dailyLimit == null ? 0 : dailyLimit);
            http().put().uri("/sessions/{id}/limits", schoolId).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
        } catch (Exception e) { log.warn("WhatsApp limit not pushed for {}: {}", schoolId, e.getMessage()); }
    }

    /**
     * Hands messages to the gateway. The gateway decides how they go out: ONE message is sent immediately
     * (when nothing is waiting and nothing was sent a moment ago); 2+ messages in one request, or messages
     * right behind each other, wait in the queue and are delivered slowly (limits, rests between batches).
     * "true" means accepted, not necessarily delivered yet. False when WhatsApp is not connected or the queue is full.
     */
    /**
     * Hands messages to the gateway and returns its per-message results (same order as the input), or null when
     * the gateway could not be reached / refused the whole request (not connected, bad key, queue full).
     * Each result looks like {to, accepted, reason?}. HTTP 200 does NOT mean every message was accepted:
     * the gateway refuses single messages (duplicate, recipient_cap, number not on WhatsApp ...) inside a 200.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> sendRaw(String schoolId, List<Map<String, String>> messages) {
        if (!isConfigured() || messages.isEmpty()) return null;
        if (isBanned(schoolId)) return null;          // banned by the super admin: nothing leaves, whatever called us
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("messages", messages);
            body.put("country", countryCode);
            WhatsAppConfig cfg = configRepo.findBySchoolId(schoolId).orElse(null);
            if (cfg != null && cfg.getDailyLimit() != null) body.put("dailyLimit", cfg.getDailyLimit());
            Map<String, Object> res = http().post().uri("/sessions/{id}/send", schoolId)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
            Object r = res == null ? null : res.get("results");
            return r instanceof List<?> ? (List<Map<String, Object>>) r : null;
        } catch (Exception e) {
            log.warn("WhatsApp send skipped for {}: {}", schoolId, e.getMessage());
            return null;
        }
    }

    private static boolean accepted(Map<String, Object> r) { return r != null && Boolean.TRUE.equals(r.get("accepted")); }

    /** True when the gateway accepted at least one message (sent now, or waiting in its queue). */
    public boolean send(String schoolId, List<Map<String, String>> messages) {
        List<Map<String, Object>> r = sendRaw(schoolId, messages);
        return r != null && r.stream().anyMatch(WhatsAppService::accepted);
    }

    /** A message that was handed to the gateway and must be logged (dedupe) once the gateway accepted it. */
    private record Pending(String key, String kind, String regNo, String phone) { }

    /**
     * Sends many messages as ONE bulk request per 500 (so the gateway queues them and paces them), and logs
     * each message as sent only after the gateway accepted its chunk. msgs and pend are index-aligned.
     */
    /** What the gateway did with a bulk request: accepted / refused counts, whether it was reachable, first refusal reason. */
    private record BatchResult(int accepted, int refused, boolean gatewayOk, String reason) { }

    private BatchResult sendBatch(String schoolId, List<Map<String, String>> msgs, List<Pending> pend) {
        int ok = 0, bad = 0;
        String reason = null;
        for (int i = 0; i < msgs.size(); i += 500) {
            int end = Math.min(msgs.size(), i + 500);
            List<Map<String, Object>> res = sendRaw(schoolId, new ArrayList<>(msgs.subList(i, end)));
            if (res == null) return new BatchResult(ok, bad, false, reason);   // not connected / bad key / queue full: nothing logged, retried next time
            for (int k = i; k < end && (k - i) < res.size(); k++) {
                Map<String, Object> r = res.get(k - i);
                Pending p = pend.get(k);
                if (accepted(r)) { record(schoolId, p.key(), p.kind(), p.regNo(), p.phone()); ok++; }
                else {
                    bad++;
                    if (reason == null && r != null) reason = String.valueOf(r.get("reason"));
                    log.info("WhatsApp refused {} for {} ({}): {}", p.kind(), p.regNo(), p.phone(), r == null ? "no result" : r.get("reason"));
                }
            }
        }
        return new BatchResult(ok, bad, true, reason);
    }

    // ─────────────── automatic notifications ───────────────

    /** Called after attendance is saved. One message per absent student per day, ever. */
    public void notifyAbsent(String schoolId, Collection<Attendance> saved) {
        if (!isConfigured() || schoolId == null || saved == null || saved.isEmpty()) return;
        if (isBanned(schoolId)) return;
        List<Attendance> copy = new ArrayList<>(saved);
        pool.submit(() -> {
            try {
                WhatsAppConfig cfg = configRepo.findBySchoolId(schoolId).orElse(null);
                if (cfg == null || !cfg.wantsAbsent()) return;
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));
                String school = schoolName(schoolId);
                List<Map<String, String>> msgs = new ArrayList<>();
                List<Pending> pend = new ArrayList<>();
                int absent = 0, notToday = 0, logged = 0, noNumber = 0;
                for (Attendance a : copy) {
                    if (a == null || a.getMemberId() == null || a.getDate() == null) continue;
                    if (!"STUDENT".equalsIgnoreCase(a.getMemberType())) continue;
                    if (!"absent".equals(ParentUtil.normalizeAttendance(a.getStatus()))) continue;
                    if (!a.getDate().equals(today)) { notToday++; continue; }
                    absent++;
                    String key = "abs:" + today + ":" + a.getMemberId();
                    if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) { logged++; continue; }
                    Student st = studentRepository.findByRegNoAndSchoolId(a.getMemberId(), schoolId).orElse(null);
                    String phone = phoneOf(st);
                    if (phone == null) { noNumber++; continue; }
                    String text = render(cfg.getAbsentTemplate(), WhatsAppConfig.DEFAULT_ABSENT, st, school,
                            Map.of("date", today.format(DAY)));
                    msgs.add(Map.of("to", phone, "text", text));
                    pend.add(new Pending(key, "ABSENT", st.getRegNo(), phone));
                }
                log.info("WhatsApp absent alerts for {}: absent today={}, to send={}, already sent earlier={}, no phone number={}, marked for another date={} (today is {})",
                        schoolId, absent, msgs.size(), logged, noNumber, notToday, today);
                // one absent student = one request (sent immediately); a whole class = one bulk request (queued, paced)
                if (!msgs.isEmpty()) sendBatch(schoolId, msgs, pend);
            } catch (Exception e) { log.warn("notifyAbsent failed: {}", e.getMessage()); }
        });
    }

    /**
     * Called when a fee voucher is generated. One message per student per month, ever (re-generating or
     * editing the voucher never re-sends). Runs in the background so it can never fail voucher generation.
     */
    public void notifyVoucher(String schoolId, String regNo, String monthKey, double total, double remaining) {
        if (!isConfigured() || schoolId == null || regNo == null) return;
        if (isBanned(schoolId)) return;
        pool.submit(() -> {
            try {
                WhatsAppConfig cfg = configRepo.findBySchoolId(schoolId).orElse(null);
                if (cfg == null || !cfg.wantsVoucher()) return;
                String key = "vch:" + regNo + ":" + monthKey;
                if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) return;
                Student st = studentRepository.findByRegNoAndSchoolId(regNo, schoolId).orElse(null);
                String phone = phoneOf(st);
                if (phone == null) return;
                waitForLedger(schoolId, regNo, monthKey);
                Map<String, String> extra = new HashMap<>(Map.of(
                        "amount", money(total), "balance", money(remaining),
                        "month", monthLabel(monthKey), "duedate", dueDate(schoolId, monthKey).format(DAY)));
                extra.putAll(feeCodes(schoolId, st, monthKey, total, remaining));
                String text = render(cfg.getVoucherTemplate(), WhatsAppConfig.DEFAULT_VOUCHER, st, schoolName(schoolId), extra);
                List<Map<String, Object>> res = sendRaw(schoolId, List.of(Map.of("to", phone, "text", text)));
                if (res != null && !res.isEmpty() && accepted(res.get(0))) {
                    record(schoolId, key, "VOUCHER", regNo, phone);
                } else {
                    log.info("WhatsApp voucher for {} not accepted: {}", regNo, res == null || res.isEmpty() ? "gateway unavailable" : res.get(0).get("reason"));
                }
            } catch (Exception e) { log.warn("notifyVoucher failed: {}", e.getMessage()); }
        });
    }

    /**
     * Daily check (10:00 Pakistan time). For every school that switched "Fee Reminder" on: if today is one of
     * the days the school chose (N days before / on / N days after the due date), message ONLY the parents whose
     * generated voucher still has a pending balance. Paid students are skipped; no one gets the same reminder twice.
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Karachi")
    public void sendFeeReminders() {
        if (!isConfigured()) return;
        for (WhatsAppConfig cfg : configRepo.findAll()) {
            if (cfg == null || !cfg.wantsReminder()) continue;
            if (isBanned(cfg.getSchoolId())) continue;
            try { remindSchool(cfg); }
            catch (Exception e) { log.warn("fee reminder failed for {}: {}", cfg.getSchoolId(), e.getMessage()); }
        }
    }

    /** "1,3,7" -> {1,3,7}. Ignores junk, keeps 1..30 only. */
    private static Set<Integer> parseDays(String csv) {
        Set<Integer> out = new TreeSet<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            try { int d = Integer.parseInt(p.trim()); if (d >= 1 && d <= 30) out.add(d); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * Decides, for today, which fee months need a reminder, using the school's own schedule:
     * N days BEFORE the due date, ON the due date, and/or N days AFTER it. The previous / current / next
     * month are all checked because "3 days before" can fall in the month before the due date and
     * "10 days after" in the month after it.
     * A school that switched reminders on but never picked a schedule keeps the old behaviour
     * (one reminder per month once the deadline + grace days have passed).
     */
    private void remindSchool(WhatsAppConfig cfg) throws Exception {
        String schoolId = cfg.getSchoolId();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Karachi"));
        Set<Integer> before = parseDays(cfg.getReminderBeforeDays());
        Set<Integer> after = parseDays(cfg.getReminderAfterDays());
        boolean onDue = Boolean.TRUE.equals(cfg.getReminderOnDue());

        if (before.isEmpty() && after.isEmpty() && !onDue) {           // legacy: no schedule chosen
            String monthKey = YearMonth.from(today).toString();
            SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
            int grace = ss == null || ss.getLateFeeGrace() == null ? 0 : ss.getLateFeeGrace();
            if (!today.isAfter(dueDate(schoolId, monthKey).plusDays(grace))) return;
            remindMonth(cfg, monthKey, "");
            return;
        }
        for (int shift = -1; shift <= 1; shift++) {
            String monthKey = YearMonth.from(today).plusMonths(shift).toString();
            long offset = ChronoUnit.DAYS.between(dueDate(schoolId, monthKey), today); // <0 before, 0 due day, >0 after
            boolean hit = offset == 0 ? onDue : offset < 0 ? before.contains((int) -offset) : after.contains((int) offset);
            if (hit) remindMonth(cfg, monthKey, ":" + offset);
        }
    }

    /** Sends the reminder for one fee month to every parent whose voucher still has a balance. */
    private void remindMonth(WhatsAppConfig cfg, String monthKey, String keySuffix) throws Exception {
        String schoolId = cfg.getSchoolId();
        String school = schoolName(schoolId);
        String dueText = dueDate(schoolId, monthKey).format(DAY);
        Set<String> regNos = new LinkedHashSet<>();
        for (Finance v : financeRepository.findByMonthKeyAndRecordTypeAndSchoolId(monthKey, Finance.TYPE_VOUCHER, schoolId)) {
            if (v.getPayloadJson() == null) continue;
            JsonNode n = objectMapper.readTree(v.getPayloadJson());
            if (n.hasNonNull("studentId")) regNos.add(n.get("studentId").asText());
        }
        List<Map<String, String>> msgs = new ArrayList<>();
        List<Pending> pend = new ArrayList<>();
        for (String regNo : regNos) {
            Finance master = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                    regNo, monthKey, Finance.TYPE_STUDENT_FEE, schoolId).orElse(null);
            if (master == null) continue;
            double net = master.getNetPayable() == null ? 0.0 : master.getNetPayable();
            double paid = master.getPaidAmount() == null ? 0.0 : master.getPaidAmount();
            double remaining = master.getRemainingBalance() == null ? Math.max(0.0, net - paid) : master.getRemainingBalance();
            if (remaining <= 0.01) continue;                                   // already paid: no reminder
            String key = "rem:" + regNo + ":" + monthKey + keySuffix;          // once per student, per month, per reminder day
            if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) continue;
            Student st = studentRepository.findByRegNoAndSchoolId(regNo, schoolId).orElse(null);
            if (st == null || (st.getStatus() != null && !"active".equalsIgnoreCase(st.getStatus()))) continue;
            String phone = phoneOf(st);
            if (phone == null) continue;
            Map<String, String> extra = new HashMap<>(Map.of(
                    "balance", money(remaining), "month", monthLabel(monthKey), "duedate", dueText));
            extra.putAll(feeCodes(schoolId, st, monthKey, null, remaining));
            String text = render(cfg.getReminderTemplate(), WhatsAppConfig.DEFAULT_REMINDER, st, school, extra);
            msgs.add(Map.of("to", phone, "text", text));
            pend.add(new Pending(key, "REMINDER", regNo, phone));
        }
        if (!msgs.isEmpty()) sendBatch(schoolId, msgs, pend);   // all reminders of this month = one bulk request
    }

    private LocalDate dueDate(String schoolId, String monthKey) {
        SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
        int day = ss == null || ss.getLateFeeDeadlineDay() == null ? 10 : ss.getLateFeeDeadlineDay();
        YearMonth ym = YearMonth.parse(monthKey);
        return ym.atDay(Math.min(Math.max(day, 1), ym.lengthOfMonth()));
    }

    private static String monthLabel(String monthKey) {
        try { return YearMonth.parse(monthKey).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)); }
        catch (Exception e) { return monthKey == null ? "" : monthKey; }
    }

    /** Manual broadcast (announcement / fee reminder) to parents of a class, a section, or everyone. */
    public Map<String, Object> broadcast(String schoolId, String className, String section, String template) {
        String school = schoolName(schoolId);
        List<Map<String, String>> msgs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int noPhone = 0;
        boolean usesCodes = template != null && FEE_CODE.matcher(normalizeTemplate(template)).find();   // fee codes work in manual messages too
        String monthKey = YearMonth.now(ZoneId.of("Asia/Karachi")).toString();
        for (Student st : studentRepository.findBySchoolId(schoolId)) {
            if (st.getStatus() != null && !"active".equalsIgnoreCase(st.getStatus())) continue;
            if (notBlank(className) && !className.equalsIgnoreCase(String.valueOf(st.getStudentClass()))) continue;
            if (notBlank(section) && !section.equalsIgnoreCase(String.valueOf(st.getSection()))) continue;
            String phone = phoneOf(st);
            if (phone == null) { noPhone++; continue; }
            // Siblings share a parent number: with {student} in the text each child still gets a message,
            // without it we send only one message per phone number.
            Map<String, String> extra = usesCodes ? feeCodes(schoolId, st, monthKey, null, null) : Map.of();
            String text = render(template, template, st, school, extra);
            if (!seen.add(phone + "|" + text)) continue;
            msgs.add(Map.of("to", phone, "text", text));
        }
        int queued = 0, refused = 0;
        boolean ok = true;
        String firstReason = null;
        for (int i = 0; i < msgs.size() && ok; i += 500) {
            List<Map<String, String>> part = msgs.subList(i, Math.min(msgs.size(), i + 500));
            List<Map<String, Object>> res = sendRaw(schoolId, new ArrayList<>(part));
            if (res == null) { ok = false; break; }
            for (Map<String, Object> r : res) {
                if (accepted(r)) queued++;
                else { refused++; if (firstReason == null && r != null) firstReason = String.valueOf(r.get("reason")); }
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("queued", queued); out.put("refused", refused);
        out.put("studentsWithoutNumber", noPhone); out.put("gatewayOk", ok);
        if (firstReason != null) out.put("reason", firstReason);
        return out;
    }

    // ─────────────── "Send to parents" popup (whatsapp-send.js) ───────────────
    public static final int MAX_PER_SEND = 100;
    private static final ZoneId PK = ZoneId.of("Asia/Karachi");
    private static final Comparator<String> CLASS_ORDER = Comparator.<String>comparingInt(c -> c.matches("\\d{1,9}") ? 0 : 1)
            .thenComparingLong(c -> c.matches("\\d{1,9}") ? Long.parseLong(c) : 0L)
            .thenComparing(String.CASE_INSENSITIVE_ORDER);

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static double remainingOf(Finance f) {
        double net = nz(f.getNetPayable()), paid = nz(f.getPaidAmount());
        return f.getRemainingBalance() == null ? Math.max(0.0, net - paid) : f.getRemainingBalance();
    }

    /** Class list, and (for a chosen class) its sections and students with their parent number, for the popup. */
    public Map<String, Object> recipients(String schoolId, String className, String section, String monthKey) {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean wantFee = notBlank(monthKey);
        for (Student st : studentRepository.findBySchoolId(schoolId)) {
            if (st.getStatus() != null && !"active".equalsIgnoreCase(st.getStatus())) continue;
            String cls = st.getStudentClass() == null ? "" : st.getStudentClass().trim();
            if (!cls.isEmpty()) classes.add(cls);
            if (!notBlank(className) || !className.trim().equalsIgnoreCase(cls)) continue;
            String sec = st.getSection() == null ? "" : st.getSection().trim();
            if (!sec.isEmpty()) sections.add(sec);
            if (notBlank(section) && !section.trim().equalsIgnoreCase(sec)) continue;
            String shown = null;
            for (String p : new String[]{st.getPhone1(), st.getPhone2()}) if (shown == null && digits(p) != null) shown = p.trim();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("regNo", st.getRegNo());
            row.put("name", st.getFullName() == null ? "" : st.getFullName());
            row.put("className", cls);
            row.put("section", sec);
            row.put("phone", shown);
            row.put("hasPhone", shown != null);
            Double bal = null;
            if (wantFee) {
                Finance f = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                        st.getRegNo(), monthKey, Finance.TYPE_STUDENT_FEE, schoolId).orElse(null);
                if (f != null) bal = remainingOf(f);
            }
            row.put("balance", bal);
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("name")), String.CASE_INSENSITIVE_ORDER));
        List<String> classList = new ArrayList<>(classes);
        classList.sort(CLASS_ORDER);
        List<String> sectionList = new ArrayList<>(sections);
        sectionList.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, Object> out = new HashMap<>();
        out.put("classes", classList);
        out.put("sections", sectionList);
        out.put("students", rows);
        out.put("maxPerSend", MAX_PER_SEND);
        return out;
    }

    /**
     * kind = announcement | fee | attendance. in = {regNos:[...], className, section, text, monthKey, onlyUnpaid, days}.
     * The gateway queues and paces everything; nothing here can bypass its limits.
     */
    public Map<String, Object> sendToParents(String schoolId, String kind, Map<String, Object> in) {
        LocalDate today = LocalDate.now(PK);
        String school = schoolName(schoolId);
        WhatsAppConfig cfg = config(schoolId);
        Set<String> regNos = new LinkedHashSet<>();
        if (in.get("regNos") instanceof List<?> l) for (Object o : l) if (o != null) regNos.add(String.valueOf(o));
        String text = str(in.get("text"));
        String monthKey = notBlank(str(in.get("monthKey"))) ? str(in.get("monthKey")) : YearMonth.now(PK).toString();
        boolean onlyUnpaid = Boolean.TRUE.equals(in.get("onlyUnpaid"));
        int days = in.get("days") instanceof Number n ? Math.max(1, Math.min(n.intValue(), 90)) : 30;

        int noPhone = 0, already = 0, noRecords = 0, noVoucher = 0, alreadyPaid = 0;
        List<Map<String, String>> msgs = new ArrayList<>();
        List<Pending> pend = new ArrayList<>();
        for (String regNo : regNos) {
            Student st = studentRepository.findByRegNoAndSchoolId(regNo, schoolId).orElse(null);
            if (st == null) continue;
            String phone = phoneOf(st);
            if (phone == null) { noPhone++; continue; }
            String key, label, body;
            if ("fee".equals(kind)) {
                Finance f = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                        regNo, monthKey, Finance.TYPE_STUDENT_FEE, schoolId).orElse(null);
                if (f == null) { noVoucher++; continue; }
                double net = nz(f.getNetPayable()), remaining = remainingOf(f);
                if (onlyUnpaid && remaining <= 0.01) { alreadyPaid++; continue; }
                Map<String, String> extra = new HashMap<>(Map.of(
                        "amount", money(net), "balance", money(remaining),
                        "month", monthLabel(monthKey), "duedate", dueDate(schoolId, monthKey).format(DAY)));
                extra.putAll(feeCodes(schoolId, st, monthKey, net, remaining));
                key = "mfee:" + regNo + ":" + monthKey + ":" + today;
                label = "FEE";
                body = render(cfg.getVoucherTemplate(), WhatsAppConfig.DEFAULT_VOUCHER, st, school, extra);
            } else if ("attendance".equals(kind)) {
                LocalDate from = today.minusDays(days - 1L);
                int pr = 0, ab = 0, lv = 0;
                for (Attendance r : attendanceRepository.findByMemberIdAndSchoolIdOrderByDateDesc(regNo, schoolId)) {
                    if (r.getDate() == null || r.getDate().isBefore(from) || r.getDate().isAfter(today)) continue;
                    if (r.getMemberType() != null && !"STUDENT".equalsIgnoreCase(r.getMemberType())) continue;
                    String v = ParentUtil.normalizeAttendance(r.getStatus());
                    if ("present".equals(v)) pr++; else if ("absent".equals(v)) ab++; else if ("leave".equals(v)) lv++;
                }
                if (pr + ab + lv == 0) { noRecords++; continue; }
                long pct = Math.round(100.0 * pr / (pr + ab + lv));
                key = "matt:" + regNo + ":" + days + ":" + today;
                label = "ATTENDANCE";
                body = "Assalam o Alaikum. Attendance report from " + school + ": " + (st.getFullName() == null ? "" : st.getFullName())
                        + " (Class " + (st.getStudentClass() == null ? "" : st.getStudentClass()) + (notBlank(st.getSection()) ? "-" + st.getSection() : "")
                        + ") for the last " + days + " days. Present: " + pr + ", Absent: " + ab + (lv > 0 ? ", Leave: " + lv : "")
                        + ". Attendance: " + pct + "%.";
            } else {
                String t = text.toLowerCase(Locale.ROOT).contains("{student}") ? text : "Dear parent of {student} ({class}),\n" + text;
                t = t + "\n\n\u2014 {school}";
                key = "man:" + regNo + ":" + today + ":" + Integer.toHexString(text.hashCode());
                label = "BROADCAST";
                body = render(t, t, st, school, Map.of());
            }
            if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) { already++; continue; }
            msgs.add(Map.of("to", phone, "text", body));
            pend.add(new Pending(key, label, regNo, phone));
        }
        BatchResult res = msgs.isEmpty() ? new BatchResult(0, 0, true, null) : sendBatch(schoolId, msgs, pend);

        Map<String, Object> skipped = new HashMap<>();
        skipped.put("noRecords", noRecords); skipped.put("noVoucher", noVoucher); skipped.put("alreadyPaid", alreadyPaid);
        Map<String, Object> out = new HashMap<>();
        out.put("queued", res.accepted());
        out.put("refused", res.refused());
        out.put("gatewayOk", res.gatewayOk());
        if (res.reason() != null) out.put("reason", res.reason());
        out.put("noPhone", noPhone);
        out.put("alreadySent", already);
        out.put("skipped", skipped);
        out.put("etaMinutes", Math.max(1, (int) Math.ceil(res.accepted() * 11 / 60.0)));
        out.put("quietNow", false);
        return out;
    }

    // ─────────────── helpers ───────────────
    private void record(String schoolId, String key, String kind, String regNo, String phone) {
        try {
            WhatsAppLog l = new WhatsAppLog();
            l.setSchoolId(schoolId); l.setDedupeKey(key); l.setKind(kind); l.setRegNo(regNo); l.setPhone(phone);
            logRepo.save(l);
        } catch (Exception ignored) { /* duplicate key = already recorded */ }
    }

    private String phoneOf(Student st) {
        if (st == null) return null;
        for (String p : new String[]{st.getPhone1(), st.getPhone2()}) {
            String d = digits(p);
            if (d != null) return d;
        }
        return null;
    }

    /** Local 03xx.. numbers become international (92 + ...); anything that is not a plausible phone is dropped. */
    String digits(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        if (d.startsWith("00")) d = d.substring(2);
        else if (d.startsWith("0")) d = countryCode + d.substring(1);
        else if (!d.startsWith(countryCode) && d.length() <= 10) d = countryCode + d;
        return d.length() >= 10 && d.length() <= 15 ? d : null;
    }

    private String render(String tpl, String fallback, Student st, String school, Map<String, String> extra) {
        String t = normalizeTemplate(notBlank(tpl) ? tpl : fallback);
        Map<String, String> v = new HashMap<>(extra);
        v.put("student", st.getFullName() == null ? "" : st.getFullName());
        v.put("class", (st.getStudentClass() == null ? "" : st.getStudentClass())
                + (notBlank(st.getSection()) ? "-" + st.getSection() : ""));
        v.put("regno", st.getRegNo() == null ? "" : st.getRegNo());
        v.put("school", school);
        v.putIfAbsent("date", LocalDate.now(ZoneId.of("Asia/Karachi")).format(DAY));
        // %[code]% student codes: work in EVERY message (absent, fee voucher, fee reminder, message to parents)
        v.put("stName", st.getFullName() == null ? "" : st.getFullName());
        v.put("stClass", st.getStudentClass() == null ? "" : st.getStudentClass());
        v.put("stSection", st.getSection() == null ? "" : st.getSection());
        v.put("grdName", st.getGuardianName() == null ? "" : st.getGuardianName());
        v.put("regNo", st.getRegNo() == null ? "" : st.getRegNo());
        return applyFormatting(applyNewLines(fillCodes(fillPlaceholders(t, v), v)));
    }

    /** The fee-ledger codes. Student codes (stName, stClass...) need no lookup, so they are not listed here. */
    private static final Pattern FEE_CODE = Pattern.compile(
            "%\\[\\s*(feeMonth|dueDate|tutionFee|arr|transportFee|ttlDisc|otherFee|fine|ttlFee|winDue|AfDue)\\s*\\]%?",
            Pattern.CASE_INSENSITIVE);

    // ─────────────── RTL / Urdu safe templates ───────────────
    /*
     * A template typed on an Urdu (right-to-left) keyboard or pasted from a phone often carries hidden
     * direction marks (LRM, RLM, ZWNJ, embeddings), Arabic look-alike symbols for * and %, full-width
     * brackets, or braces/brackets typed in mirrored order. normalizeTemplate() turns all of that back
     * into the exact ASCII tags and codes the rest of this class understands, so formatting, new lines,
     * {placeholders} and %[codes]% behave the same in Urdu as in English.
     */
    private static final String INVC = "\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF\\u061C";
    private static final String INV = "[" + INVC + "]*";
    private static final String GAP = "[ \\t\\u00A0" + INVC + "]*";
    private static final String SPC = "[\\s\\u00A0\\u202F" + INVC + "]*";
    private static final String STAR = "[*\\u066D\\uFF0A]";
    private static final String PCT = "[%\\u066A\\uFF05]";
    private static final String BRACE = "[{}\\uFF5B\\uFF5D]";
    private static final String SQUARE = "[\\[\\]\\uFF3B\\uFF3D]";
    private static final String SLASH = "[/\\\\\\uFF0F]";

    private static final Pattern NL_TAG_LOOSE = Pattern.compile(
            GAP + STAR + INV + BRACE + INV + "n" + INV + "l" + INV + BRACE + INV + STAR + GAP,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FMT_TAG_LOOSE = Pattern.compile(
            STAR + INV + BRACE + INV + "(" + SLASH + ")?" + INV + "([bis])" + INV + BRACE + INV + STAR,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_LOOSE = Pattern.compile(
            PCT + INV + SQUARE + SPC + "([A-Za-z]+)" + SPC + SQUARE + INV + "(" + PCT + ")?");
    private static final Pattern PLACEHOLDER_LOOSE = Pattern.compile(
            BRACE + INV + "([A-Za-z]+)" + INV + BRACE);

    private static boolean isInvisible(char c) {
        return (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2060 && c <= 0x2069) || c == 0xFEFF || c == 0x061C;
    }

    /** Rewrites the loose (Urdu keyboard) forms of *{nL}*, *{b}*...*{/b}* and %[code]% into the exact ASCII forms. */
    static String normalizeTemplate(String text) {
        if (text == null || text.isEmpty()) return text;
        String t = NL_TAG_LOOSE.matcher(text).replaceAll(Matcher.quoteReplacement("*{nL}*"));
        Matcher f = FMT_TAG_LOOSE.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (f.find()) {
            String slash = f.group(1) == null ? "" : "/";
            String letter = f.group(2).toLowerCase(Locale.ROOT);
            f.appendReplacement(sb, Matcher.quoteReplacement("*{" + slash + letter + "}*"));
        }
        f.appendTail(sb);
        t = sb.toString();
        Matcher c = CODE_LOOSE.matcher(t);
        sb = new StringBuilder();
        while (c.find()) {
            c.appendReplacement(sb, Matcher.quoteReplacement("%[" + c.group(1) + "]" + (c.group(2) != null ? "%" : "")));
        }
        c.appendTail(sb);
        return sb.toString();
    }

    /** One-pass {student} {class} {school} ... replacement; names are case-insensitive, unknown ones stay as typed. */
    private static String fillPlaceholders(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) return text;
        Map<String, String> byKey = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) byKey.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        Matcher m = PLACEHOLDER_LOOSE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String val = byKey.get(m.group(1).toLowerCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(val == null ? m.group() : val));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ─────────────── new line tag: Fee Month *{nL}* Due Date  ->  two lines ───────────────
    /**
     * Opt-in: only the exact code *{nL}* (any letter case) is touched. A message without it is sent exactly
     * as written. Spaces around the tag are dropped so lines start/end clean.
     */
    private static final Pattern NEWLINE = Pattern.compile("[ \\t]*\\*\\{nl\\}\\*[ \\t]*", Pattern.CASE_INSENSITIVE);

    /** Replaces every *{nL}* with a real line break. Runs before applyFormatting so styles wrap per line. */
    static String applyNewLines(String text) {
        text = normalizeTemplate(text);
        if (text == null || text.indexOf('{') < 0) return text;
        return NEWLINE.matcher(text).replaceAll("\n");
    }

    // ─────────────── text formatting tags: *{b}*bold*{/b}*  *{i}*italic*{/i}*  *{s}*strike*{/s}* ───────────────
    /**
     * Opt-in: nothing happens unless the school writes the full, exact pair *{b}*text*{/b}* (same for i, s).
     * Plain text, a lone tag, or a bare {b} is left exactly as typed.
     */
    private static final Pattern FMT = Pattern.compile(
            "\\*\\{(b|i|s)\\}\\*(.*?)\\*\\{/\\1\\}\\*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Turns the tags into WhatsApp formatting: b = *bold*, i = _italic_, s = ~strike~.
     * WhatsApp only formats when the marker touches the text, so spaces just inside a tag
     * ("*{b}*Fee Month *{/b}*") are moved outside the marker, and each line is wrapped on its own
     * because WhatsApp formatting cannot span a line break.
     */
    static String applyFormatting(String text) {
        text = normalizeTemplate(text);
        if (text == null || text.indexOf('{') < 0) return text;
        Matcher m = FMT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            char t = Character.toLowerCase(m.group(1).charAt(0));
            String mark = t == 'b' ? "*" : t == 'i' ? "_" : "~";
            String[] lines = m.group(2).split("\n", -1);
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) r.append('\n');
                String line = lines[i];
                String core = line.strip();
                int a = 0, b = core.length();
                while (a < b && isInvisible(core.charAt(a))) a++;
                while (b > a && isInvisible(core.charAt(b - 1))) b--;
                core = core.substring(a, b).strip();
                if (core.isEmpty()) { r.append(line); continue; }
                int lead = line.length() - line.stripLeading().length();
                int trail = line.length() - line.stripTrailing().length();
                r.append(line, 0, lead).append(mark).append(core).append(mark).append(line.substring(line.length() - trail));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ─────────────── %[code]% placeholders (Settings > WhatsApp > "Codes" button) ───────────────
    /** %[stName]%  (the closing % is optional so a typo like %[fine] still works). Names are case-insensitive. */
    private static final Pattern CODE = Pattern.compile("%\\[\\s*([A-Za-z]+)\\s*\\]%?");

    /** Maps each %[code]% to its value; a code we do not know is left in the text untouched. */
    static String fillCodes(String text, Map<String, String> values) {
        if (text == null || text.indexOf("%[") < 0) return text;
        Map<String, String> byCode = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) byCode.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        Matcher m = CODE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String val = byCode.get(m.group(1).toLowerCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(val == null ? m.group() : val));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Values for the fee codes, read from the student's saved fee ledger row for that month:
     * (student codes stName stClass stSection grdName regNo are added by render() for every message)
     * stName feeMonth dueDate tutionFee arr transportFee ttlDisc otherFee fine ttlFee winDue AfDue.
     *  - arr      = unpaid balance carried over from the previous month (never more than the row's "other charges")
     *  - otherFee = the rest of the row's "other charges" (custom fee rows)
     *  - ttlFee   = the voucher total (tuition + transport + other + fine - discount)
     *  - winDue   = what is still payable on or before the due date
     *  - AfDue    = winDue + the late fee set in Settings > Late Fee (same as winDue when late fee is off)
     */
    private Map<String, String> feeCodes(String schoolId, Student st, String monthKey,
                                         Double fallbackTotal, Double fallbackRemaining) {
        Map<String, String> c = new HashMap<>();
        // These three never depend on the ledger, so they always work (before, a missing ledger row left
        // even %[stName]% unreplaced in the message).
        c.put("stName", st.getFullName() == null ? "" : st.getFullName());
        c.put("feeMonth", monthLabel(monthKey));
        c.put("dueDate", dueDate(schoolId, monthKey).format(DAY));
        Finance f = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                st.getRegNo(), monthKey, Finance.TYPE_STUDENT_FEE, schoolId).orElse(null);
        if (f == null) {
            // No fee row for this month (yet): amounts that are unknown show 0 instead of a raw %[code]%.
            double rem = fallbackRemaining == null ? 0.0 : fallbackRemaining;
            for (String k : new String[]{"tutionFee", "arr", "transportFee", "ttlDisc", "otherFee", "fine"}) c.put(k, "0");
            c.put("ttlFee", money(fallbackTotal == null ? rem : fallbackTotal));
            c.put("winDue", money(rem));
            c.put("AfDue", money(rem + lateFeeFor(schoolId, 0.0)));
            return c;
        }
        double tuition = nz(f.getBaseTuitionFee());
        double other = nz(f.getOtherCharges());
        double arrears = Math.min(other, previousBalance(schoolId, st, monthKey));
        double fine = Math.max(nz(f.getTotalFineCharged()), nz(f.getFineAmount()));
        double payable = nz(f.getRemainingBalance());

        double late = lateFeeFor(schoolId, tuition);
        c.put("tutionFee", money(tuition));
        c.put("arr", money(arrears));
        c.put("transportFee", money(nz(f.getTransportFee())));
        c.put("ttlDisc", money(nz(f.getTotalDiscountApplied())));
        c.put("otherFee", money(Math.max(0.0, other - arrears)));
        c.put("fine", money(fine));
        c.put("ttlFee", money(nz(f.getNetPayable())));
        c.put("winDue", money(payable));
        c.put("AfDue", money(payable + late));
        return c;
    }

    /** Late fee from Settings > Late Fee (fixed amount, or percent of tuition); 0 when late fee is off. */
    private double lateFeeFor(String schoolId, double tuition) {
        SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
        if (ss == null || Boolean.FALSE.equals(ss.getLateFeeEnabled()) || ss.getLateFeeAmount() == null) return 0.0;
        return "percent".equalsIgnoreCase(ss.getLateFeeType())
                ? tuition * ss.getLateFeeAmount() / 100.0 : ss.getLateFeeAmount();
    }

    /**
     * The voucher message is sent from a background thread the moment the voucher is generated, so the
     * student's fee row may not be committed yet. Wait briefly (max ~4 s) so the codes get the real values.
     */
    private void waitForLedger(String schoolId, String regNo, String monthKey) {
        for (int i = 0; i < 8; i++) {
            if (financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                    regNo, monthKey, Finance.TYPE_STUDENT_FEE, schoolId).isPresent()) return;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Unpaid balance of the latest earlier month, or the student's opening arrears if there is none. */
    private double previousBalance(String schoolId, Student st, String monthKey) {
        Finance latest = null;
        for (Finance r : financeRepository.findBySchoolIdAndRegNo(schoolId, st.getRegNo())) {
            if (!Finance.TYPE_STUDENT_FEE.equals(r.getRecordType()) || r.getMonthKey() == null
                    || r.getMonthKey().compareTo(monthKey) >= 0) continue;
            if (latest == null || r.getMonthKey().compareTo(latest.getMonthKey()) > 0) latest = r;
        }
        return latest != null ? nz(latest.getRemainingBalance()) : nz(st.getArrears());
    }

    private static double nz(Double d) { return d == null ? 0.0 : d; }

    private String schoolName(String schoolId) {
        return settingsRepository.findBySchoolId(schoolId).map(SchoolSettings::getSchoolName)
                .filter(s -> s != null && !s.isBlank()).orElse("the school");
    }

    private static String money(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.2f", d); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
