package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.Finance;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Student;
import com.softschool.backend.model.WhatsAppConfig;
import com.softschool.backend.model.WhatsAppLog;
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
     * Hands messages to the gateway QUEUE. The gateway delivers them slowly and later (limits, rests, quiet
     * hours), so "true" means accepted, not delivered. False when WhatsApp is not connected or the queue is full.
     */
    @SuppressWarnings("unchecked")
    public boolean send(String schoolId, List<Map<String, String>> messages) {
        if (!isConfigured() || messages.isEmpty()) return false;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("messages", messages);
            body.put("country", countryCode);
            WhatsAppConfig cfg = configRepo.findBySchoolId(schoolId).orElse(null);
            if (cfg != null && cfg.getDailyLimit() != null) body.put("dailyLimit", cfg.getDailyLimit());
            http().post().uri("/sessions/{id}/send", schoolId).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("WhatsApp send skipped for {}: {}", schoolId, e.getMessage());
            return false;
        }
    }

    // ─────────────── automatic notifications ───────────────

    /** Called after attendance is saved. One message per absent student per day, ever. */
    public void notifyAbsent(String schoolId, Collection<Attendance> saved) {
        if (!isConfigured() || schoolId == null || saved == null || saved.isEmpty()) return;
        List<Attendance> copy = new ArrayList<>(saved);
        pool.submit(() -> {
            try {
                WhatsAppConfig cfg = configRepo.findBySchoolId(schoolId).orElse(null);
                if (cfg == null || !cfg.wantsAbsent()) return;
                LocalDate today = LocalDate.now();
                String school = schoolName(schoolId);
                for (Attendance a : copy) {
                    if (a == null || a.getMemberId() == null || a.getDate() == null || !a.getDate().equals(today)) continue;
                    if (!"STUDENT".equalsIgnoreCase(a.getMemberType())) continue;
                    if (!"absent".equals(ParentUtil.normalizeAttendance(a.getStatus()))) continue;
                    String key = "abs:" + today + ":" + a.getMemberId();
                    if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) continue;
                    Student st = studentRepository.findByRegNoAndSchoolId(a.getMemberId(), schoolId).orElse(null);
                    String phone = phoneOf(st);
                    if (phone == null) continue;
                    String text = render(cfg.getAbsentTemplate(), WhatsAppConfig.DEFAULT_ABSENT, st, school,
                            Map.of("date", today.format(DAY)));
                    if (send(schoolId, List.of(Map.of("to", phone, "text", text)))) {
                        record(schoolId, key, "ABSENT", st.getRegNo(), phone);
                    }
                }
            } catch (Exception e) { log.warn("notifyAbsent failed: {}", e.getMessage()); }
        });
    }

    /**
     * Called when a fee voucher is generated. One message per student per month, ever (re-generating or
     * editing the voucher never re-sends). Runs in the background so it can never fail voucher generation.
     */
    public void notifyVoucher(String schoolId, String regNo, String monthKey, double total, double remaining) {
        if (!isConfigured() || schoolId == null || regNo == null) return;
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
                if (send(schoolId, List.of(Map.of("to", phone, "text", text)))) {
                    record(schoolId, key, "VOUCHER", regNo, phone);
                }
            } catch (Exception e) { log.warn("notifyVoucher failed: {}", e.getMessage()); }
        });
    }

    /**
     * Daily check (10:00 Pakistan time). For every school that switched "Fee Reminder" on: once the
     * deadline (+ grace days) of the current month has passed, message ONLY the parents whose generated
     * voucher still has a pending balance. Paid students are skipped; each student is reminded once per month.
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Karachi")
    public void sendFeeReminders() {
        if (!isConfigured()) return;
        for (WhatsAppConfig cfg : configRepo.findAll()) {
            if (cfg == null || !cfg.wantsReminder()) continue;
            try { remindSchool(cfg); }
            catch (Exception e) { log.warn("fee reminder failed for {}: {}", cfg.getSchoolId(), e.getMessage()); }
        }
    }

    private void remindSchool(WhatsAppConfig cfg) throws Exception {
        String schoolId = cfg.getSchoolId();
        LocalDate today = LocalDate.now();
        String monthKey = YearMonth.from(today).toString();
        SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
        int grace = ss == null || ss.getLateFeeGrace() == null ? 0 : ss.getLateFeeGrace();
        LocalDate due = dueDate(schoolId, monthKey);
        if (!today.isAfter(due.plusDays(grace))) return; // deadline has not passed yet

        String school = schoolName(schoolId);
        String dueText = due.format(DAY);
        Set<String> regNos = new LinkedHashSet<>();
        for (Finance v : financeRepository.findByMonthKeyAndRecordTypeAndSchoolId(monthKey, Finance.TYPE_VOUCHER, schoolId)) {
            if (v.getPayloadJson() == null) continue;
            JsonNode n = objectMapper.readTree(v.getPayloadJson());
            if (n.hasNonNull("studentId")) regNos.add(n.get("studentId").asText());
        }
        for (String regNo : regNos) {
            Finance master = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                    regNo, monthKey, Finance.TYPE_STUDENT_FEE, schoolId).orElse(null);
            if (master == null) continue;
            double net = master.getNetPayable() == null ? 0.0 : master.getNetPayable();
            double paid = master.getPaidAmount() == null ? 0.0 : master.getPaidAmount();
            double remaining = master.getRemainingBalance() == null ? Math.max(0.0, net - paid) : master.getRemainingBalance();
            if (remaining <= 0.01) continue;                                   // already paid: no reminder
            String key = "rem:" + regNo + ":" + monthKey;
            if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) continue; // already reminded this month
            Student st = studentRepository.findByRegNoAndSchoolId(regNo, schoolId).orElse(null);
            if (st == null || (st.getStatus() != null && !"active".equalsIgnoreCase(st.getStatus()))) continue;
            String phone = phoneOf(st);
            if (phone == null) continue;
            Map<String, String> extra = new HashMap<>(Map.of(
                    "balance", money(remaining), "month", monthLabel(monthKey), "duedate", dueText));
            extra.putAll(feeCodes(schoolId, st, monthKey, null, remaining));
            String text = render(cfg.getReminderTemplate(), WhatsAppConfig.DEFAULT_REMINDER, st, school, extra);
            if (send(schoolId, List.of(Map.of("to", phone, "text", text)))) {
                record(schoolId, key, "REMINDER", regNo, phone);
            }
        }
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
        boolean usesCodes = template != null && template.contains("%[");   // fee codes work in manual messages too
        String monthKey = YearMonth.now().toString();
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
        int queued = 0;
        boolean ok = true;
        for (int i = 0; i < msgs.size() && ok; i += 500) {
            List<Map<String, String>> part = msgs.subList(i, Math.min(msgs.size(), i + 500));
            ok = send(schoolId, part);
            if (ok) queued += part.size();
        }
        return Map.of("queued", queued, "studentsWithoutNumber", noPhone, "gatewayOk", ok);
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
        String t = notBlank(tpl) ? tpl : fallback;
        Map<String, String> v = new HashMap<>(extra);
        v.put("student", st.getFullName() == null ? "" : st.getFullName());
        v.put("class", (st.getStudentClass() == null ? "" : st.getStudentClass())
                + (notBlank(st.getSection()) ? "-" + st.getSection() : ""));
        v.put("regno", st.getRegNo() == null ? "" : st.getRegNo());
        v.put("school", school);
        v.putIfAbsent("date", LocalDate.now().format(DAY));
        for (Map.Entry<String, String> e : v.entrySet()) t = t.replace("{" + e.getKey() + "}", e.getValue());
        return applyFormatting(applyNewLines(fillCodes(t, v)));
    }

    // ─────────────── new line tag: Fee Month *{nL}* Due Date  ->  two lines ───────────────
    /**
     * Opt-in: only the exact code *{nL}* (any letter case) is touched. A message without it is sent exactly
     * as written. Spaces around the tag are dropped so lines start/end clean.
     */
    private static final Pattern NEWLINE = Pattern.compile("[ \\t]*\\*\\{nl\\}\\*[ \\t]*", Pattern.CASE_INSENSITIVE);

    /** Replaces every *{nL}* with a real line break. Runs before applyFormatting so styles wrap per line. */
    static String applyNewLines(String text) {
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
