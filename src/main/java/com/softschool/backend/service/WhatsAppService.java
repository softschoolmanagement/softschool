package com.softschool.backend.service;

import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Student;
import com.softschool.backend.model.WhatsAppConfig;
import com.softschool.backend.model.WhatsAppLog;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Hands messages to the gateway queue. Returns false when the school's WhatsApp is not connected. */
    @SuppressWarnings("unchecked")
    public boolean send(String schoolId, List<Map<String, String>> messages) {
        if (!isConfigured() || messages.isEmpty()) return false;
        try {
            http().post().uri("/sessions/{id}/send", schoolId).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messages", messages, "country", countryCode)).retrieve().toBodilessEntity();
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

    /** Called after a fee payment is recorded. */
    public void notifyFeePaid(String schoolId, String regNo, String monthKey, double amount, double remaining) {
        if (!isConfigured() || schoolId == null || regNo == null) return;
        pool.submit(() -> {
            try {
                WhatsAppConfig cfg = configRepo.findBySchoolId(schoolId).orElse(null);
                if (cfg == null || !cfg.wantsFee()) return;
                Student st = studentRepository.findByRegNoAndSchoolId(regNo, schoolId).orElse(null);
                String phone = phoneOf(st);
                if (phone == null) return;
                String key = "pay:" + regNo + ":" + monthKey + ":" + amount + ":" + (System.currentTimeMillis() / 60000);
                if (logRepo.existsBySchoolIdAndDedupeKey(schoolId, key)) return; // double-click guard
                String text = render(cfg.getFeeTemplate(), WhatsAppConfig.DEFAULT_FEE, st, schoolName(schoolId), Map.of(
                        "amount", money(amount), "balance", money(remaining), "month", monthKey == null ? "" : monthKey));
                if (send(schoolId, List.of(Map.of("to", phone, "text", text)))) {
                    record(schoolId, key, "FEE", regNo, phone);
                }
            } catch (Exception e) { log.warn("notifyFeePaid failed: {}", e.getMessage()); }
        });
    }

    /** Manual broadcast (announcement / fee reminder) to parents of a class, a section, or everyone. */
    public Map<String, Object> broadcast(String schoolId, String className, String section, String template) {
        String school = schoolName(schoolId);
        List<Map<String, String>> msgs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int noPhone = 0;
        for (Student st : studentRepository.findBySchoolId(schoolId)) {
            if (st.getStatus() != null && !"active".equalsIgnoreCase(st.getStatus())) continue;
            if (notBlank(className) && !className.equalsIgnoreCase(String.valueOf(st.getStudentClass()))) continue;
            if (notBlank(section) && !section.equalsIgnoreCase(String.valueOf(st.getSection()))) continue;
            String phone = phoneOf(st);
            if (phone == null) { noPhone++; continue; }
            // Siblings share a parent number: with {student} in the text each child still gets a message,
            // without it we send only one message per phone number.
            String text = render(template, template, st, school, Map.of());
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
        return t;
    }

    private String schoolName(String schoolId) {
        return settingsRepository.findBySchoolId(schoolId).map(SchoolSettings::getSchoolName)
                .filter(s -> s != null && !s.isBlank()).orElse("the school");
    }

    private static String money(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.2f", d); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
