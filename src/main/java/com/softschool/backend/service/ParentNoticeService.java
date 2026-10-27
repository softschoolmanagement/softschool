package com.softschool.backend.service;

import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.Message;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Student;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.repository.MessageRepository;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Messages the school office sends to a student's parent about ATTENDANCE. They land in the
 * parent's "School Office" chat (channel parent_admin), the same place result notices go, so the
 * parent portal needs no change to show them.
 *
 *   1. Daily absent alert   - automatic, only when Settings > Attendance Setting has it switched on.
 *                             Called from AttendanceController after student attendance is saved.
 *   2. Weekly / monthly report - sent on demand by the admin from the Attendance page.
 */
@Service
public class ParentNoticeService {

    private static final String SENDER = "Admin";
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    @Autowired private SchoolSettingsRepository settingsRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private AttendanceRepository attendanceRepository;

    // ───────────────────────── 1. daily absent alert ─────────────────────────

    /**
     * Call after attendance rows were saved. For every STUDENT marked absent TODAY the parent gets one
     * alert (never a second one for the same day, however often the sheet is re-saved). If a student is
     * later corrected to present/leave, an alert the parent has not opened yet is removed; one that was
     * already opened is followed by a short correction. Does nothing while the setting is off.
     * Never throws: a notice problem must not make saving attendance fail.
     */
    @Transactional
    public int syncAbsentAlerts(String schoolId, Collection<Attendance> saved) {
        try {
            if (schoolId == null || saved == null || saved.isEmpty()) return 0;
            SchoolSettings ss = settingsRepository.findBySchoolId(schoolId).orElse(null);
            if (ss == null || !Boolean.TRUE.equals(ss.getDailyAbsentNotify())) return 0;

            LocalDate today = LocalDate.now();
            String group = newGroupId();
            List<Message> add = new ArrayList<>();
            List<Message> drop = new ArrayList<>();
            for (Attendance a : saved) {
                if (a == null || a.getMemberId() == null || a.getDate() == null || !a.getDate().equals(today)) continue;
                if (!"STUDENT".equalsIgnoreCase(a.getMemberType())) continue;
                boolean absent = "absent".equals(ParentUtil.normalizeAttendance(a.getStatus()));
                String key = key("abs", a.getDate(), a.getMemberId());
                Optional<Message> sent = messageRepository.findBySchoolIdAndStaffIdAndClientId(schoolId, "", key);
                if (absent && sent.isEmpty()) {
                    Student st = studentRepository.findByRegNoAndSchoolId(a.getMemberId(), schoolId).orElse(null);
                    if (st == null) continue;
                    add.add(notice(st, group, key, "Attendance alert: " + name(st) + " was marked ABSENT today ("
                            + a.getDate().format(LONG_DAY) + "). If this is a mistake or your child is on approved leave, "
                            + "please contact the school office."));
                } else if (!absent && sent.isPresent()) {
                    Message old = sent.get();
                    if (!old.isReadByParent()) {
                        drop.add(old);
                    } else {
                        String fix = key("fix", a.getDate(), a.getMemberId());
                        if (messageRepository.findBySchoolIdAndStaffIdAndClientId(schoolId, "", fix).isEmpty()) {
                            Student st = studentRepository.findByRegNoAndSchoolId(a.getMemberId(), schoolId).orElse(null);
                            if (st != null) {
                                add.add(notice(st, group, fix, "Correction: " + name(st) + " is NOT absent today ("
                                        + a.getDate().format(LONG_DAY) + "). Please ignore the earlier absence alert."));
                            }
                        }
                    }
                }
            }
            if (!drop.isEmpty()) messageRepository.deleteAll(drop);
            if (!add.isEmpty()) messageRepository.saveAll(add);
            return add.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ───────────────────────── 2. weekly / monthly report ─────────────────────────

    /** The dates a report covers: the week (Mon-Sun) or month that contains {@code ref}, never past today. */
    public static LocalDate[] reportRange(String period, LocalDate ref) {
        LocalDate today = LocalDate.now();
        if (ref == null || ref.isAfter(today)) ref = today;
        LocalDate from, to;
        if ("monthly".equals(period)) {
            from = ref.withDayOfMonth(1);
            to = ref.with(TemporalAdjusters.lastDayOfMonth());
        } else {
            from = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            to = from.plusDays(6);
        }
        if (to.isAfter(today)) to = today;
        return new LocalDate[]{from, to};
    }

    /**
     * Sends each student's attendance report for the period to the parent. Students with no attendance
     * recorded in the period are skipped (nothing to report). Returns { sent, skipped, from, to, period }.
     */
    @Transactional
    public Map<String, Object> sendReports(String schoolId, String period, LocalDate ref, Collection<Student> students) {
        LocalDate[] r = reportRange(period, ref);
        LocalDate from = r[0], to = r[1];
        boolean monthly = "monthly".equals(period);

        Map<String, List<Attendance>> byStudent = new HashMap<>();
        for (Attendance a : attendanceRepository.findByMemberTypeAndSchoolIdAndDateBetween("STUDENT", schoolId, from, to)) {
            if (a.getMemberId() != null) byStudent.computeIfAbsent(a.getMemberId(), k -> new ArrayList<>()).add(a);
        }

        String group = newGroupId();
        List<Message> out = new ArrayList<>();
        int skipped = 0;
        for (Student st : students) {
            List<Attendance> rows = byStudent.get(st.getRegNo());
            if (rows == null || rows.isEmpty()) { skipped++; continue; }
            out.add(notice(st, group, null, reportText(st, rows, monthly, from, to)));
        }
        if (!out.isEmpty()) messageRepository.saveAll(out);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("period", monthly ? "monthly" : "weekly");
        res.put("from", from.toString());
        res.put("to", to.toString());
        res.put("sent", out.size());
        res.put("skipped", skipped);
        return res;
    }

    private String reportText(Student st, List<Attendance> rows, boolean monthly, LocalDate from, LocalDate to) {
        int present = 0, absent = 0, leave = 0, late = 0;
        List<LocalDate> absentDays = new ArrayList<>();
        rows.sort(Comparator.comparing(Attendance::getDate));
        for (Attendance a : rows) {
            String s = ParentUtil.normalizeAttendance(a.getStatus());
            if ("present".equals(s)) {
                present++;
                if (a.getStatus() != null && a.getStatus().toLowerCase(Locale.ROOT).contains("late")) late++;
            } else if ("absent".equals(s)) {
                absent++;
                absentDays.add(a.getDate());
            } else if ("leave".equals(s)) {
                leave++;
            }
        }
        int total = present + absent + leave;
        long pct = total == 0 ? 0 : Math.round(present * 100.0 / total);

        StringBuilder b = new StringBuilder();
        b.append(monthly ? "Monthly" : "Weekly").append(" Attendance Report\n");
        b.append("Student: ").append(name(st)).append(" (").append(classLabel(st)).append(")\n");
        b.append("Period: ").append(from.format(SHORT_DAY)).append(" - ").append(to.format(SHORT_DAY_YEAR)).append("\n\n");
        b.append("Present: ").append(present).append("   Absent: ").append(absent).append("   Leave: ").append(leave);
        if (late > 0) b.append("   Late: ").append(late);
        b.append("\nAttendance: ").append(pct).append("% (").append(present).append(" of ").append(total).append(" marked days)");
        if (!absentDays.isEmpty()) {
            b.append("\n\nAbsent on: ");
            int shown = Math.min(absentDays.size(), 12);
            for (int i = 0; i < shown; i++) {
                if (i > 0) b.append(", ");
                b.append(absentDays.get(i).format(SHORT_DAY));
            }
            if (absentDays.size() > shown) b.append(" and ").append(absentDays.size() - shown).append(" more");
        }
        return ParentUtil.clip(b.toString(), 1000);
    }

    // ───────────────────────── helpers ─────────────────────────

    private Message notice(Student st, String groupId, String clientId, String body) {
        Message m = new Message();
        m.setSchoolId(st.getSchoolId());
        m.setStaffId("");
        m.setChannel(Message.CH_PARENT_ADMIN);
        m.setSenderType(Message.FROM_ADMIN);
        m.setSenderName(SENDER);
        m.setReadByAdmin(true);
        m.setBroadcastId(groupId);
        m.setClientId(clientId);
        m.setStudentRegNo(st.getRegNo());
        m.setStudentName(st.getFullName());
        m.setGuardianName(st.getGuardianName());
        m.setGuardianRole(st.getGuardianRole());
        m.setClassName(classLabel(st));
        m.setBody(ParentUtil.clip(body, 1000));
        return m;
    }

    private static String classLabel(Student st) {
        String cls = st.getStudentClass() == null ? "" : st.getStudentClass().trim();
        String sec = st.getSection() == null ? "" : st.getSection().trim();
        return sec.isEmpty() ? cls : cls + " - " + sec;
    }

    private static String name(Student st) {
        return st.getFullName() == null || st.getFullName().isBlank() ? String.valueOf(st.getRegNo()) : st.getFullName().trim();
    }

    private static String newGroupId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    /** Stable id of "this notice, for this student, on this day" (fits Message.clientId, max 40). */
    private static String key(String kind, LocalDate date, String regNo) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(String.valueOf(regNo).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) hex.append(String.format("%02x", d[i]));
            return kind + date.toString().replace("-", "") + "-" + hex;
        } catch (Exception e) {
            return kind + date.toString().replace("-", "") + "-" + Math.abs(String.valueOf(regNo).hashCode());
        }
    }
}
