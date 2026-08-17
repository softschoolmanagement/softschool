package com.softschool.backend.model;

import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.repository.StaffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
public class ZktecoService {

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private AttendanceRepository attendanceRepository;

    private volatile String mdbPath = "";

    // NEW: the school this biometric device is linked to. Staff is now
    // identified by (schoolId, staffId) — see Staff.java / StaffRepository —
    // so we need this to resolve punches correctly, and to keep School A's
    // device from ever matching School B's staff.
    private volatile String schoolId = "";

    public void updateMdbPath(String newPath, String schoolId) {
        this.mdbPath = newPath;
        this.schoolId = schoolId;
        System.out.println("✅ Biometric Path Updated to: " + newPath + " (school: " + schoolId + ")");
    }

    @Scheduled(fixedRate = 15000)
    public void syncFromZKSoftware() {
        if (mdbPath == null || mdbPath.isEmpty()) return;
        if (schoolId == null || schoolId.isEmpty()) return;

        String dbUrl = "jdbc:ucanaccess://" + mdbPath + ";readOnly=true";

        // We calculate "Today at 00:00:00" in Java to avoid the SQL DATE error
        Timestamp todayStart = Timestamp.valueOf(LocalDate.now().atStartOfDay());

        String sql = "SELECT USERINFO.Badgenumber, USERINFO.Name, " +
                     "MIN(CHECKINOUT.CHECKTIME) as FirstPunch, " +
                     "MAX(CHECKINOUT.CHECKTIME) as LastPunch " +
                     "FROM CHECKINOUT " +
                     "LEFT JOIN USERINFO ON CHECKINOUT.USERID = USERINFO.USERID " +
                     "WHERE CHECKINOUT.CHECKTIME >= ? " +
                     "GROUP BY USERINFO.Badgenumber, USERINFO.Name";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // Pass the Java-calculated date to the query
            pstmt.setTimestamp(1, todayStart);

            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String badge = rs.getString("Badgenumber");
                String name = rs.getString("Name");
                Timestamp firstPunch = rs.getTimestamp("FirstPunch");
                Timestamp lastPunch = rs.getTimestamp("LastPunch");

                if (badge != null && firstPunch != null) {
                    processPunchesForSchool(schoolId, badge, name, firstPunch, lastPunch);
                }
            }
        } catch (Exception e) {
            // We ignore common Access "File Busy" warnings to keep terminal clean
            if (!e.getMessage().contains("user lacks privilege")) {
                System.err.println("⚠️ Biometric Bridge: " + e.getMessage());
            }
        }
    }

    /**
     * badgeNumber is expected to match Staff.staffId (e.g. "PSC_S_1") for the
     * school currently linked via updateMdbPath — NOT a global/internal id.
     */
    public void markAttendance(String badgeNumber, String staffName, LocalTime scanTime) {
        if (schoolId == null || schoolId.isEmpty()) {
            System.out.println("❌ Test Failed: No school linked to this biometric device yet.");
            return;
        }

        LocalDate today = LocalDate.now();

        Optional<Staff> staffOpt = resolveStaff(badgeNumber, schoolId);
        if (staffOpt.isPresent()) {
            Staff s = staffOpt.get();
            // Attendance.memberId is a String and stores Staff.staffId (the
            // public "PSC_S_1"-style id), not the internal Long id — see
            // Attendance.java / AttendanceRepository.findByMemberIdAndDateAndSchoolId.
            Optional<Attendance> existingAtt = attendanceRepository.findByMemberIdAndDateAndSchoolId(s.getStaffId(), today, schoolId);

            if (existingAtt.isEmpty()) {
                // Test Check-In
                Attendance att = new Attendance();
                att.setSchoolId(schoolId);
                att.setMemberId(s.getStaffId());
                att.setMemberName(s.getName());
                att.setMemberType("STAFF");
                att.setRole(resolveRole(s));
                att.setDate(today);
                att.setStatus("present");
                att.setCheckIn(scanTime);
                att.setReason("Manual/Test Punch");
                attendanceRepository.save(att);
                System.out.println("☀️ Manual Check-In: " + s.getName() + " at " + scanTime);
            } else {
                // Test Check-Out
                Attendance att = existingAtt.get();
                if (att.getCheckOut() == null && scanTime.isAfter(att.getCheckIn())) {
                    att.setCheckOut(scanTime);
                    att.setReason("Manual/Test Punch Out");
                    attendanceRepository.save(att);
                    System.out.println("🌙 Manual Check-Out: " + s.getName() + " at " + scanTime);
                }
            }
        } else {
            System.out.println("❌ Test Failed: No staff found with staffId " + badgeNumber + " in school " + schoolId);
        }
    }

    /**
     * Public entry point for external sources (currently: the local
     * zkteco_sync_agent.py script, via BiometricController#syncPunches)
     * that have already read today's punches out of the ZKTeco Time
     * software's database themselves and just need them applied using the
     * exact same rules as the old in-process syncFromZKSoftware() used —
     * badge resolution, "don't overwrite manual absent/leave", and
     * check-in-then-check-out semantics.
     *
     * schoolId is taken from the request rather than the mutable
     * this.schoolId field, so this works correctly even if /link was never
     * called (which is now the normal case, since the backend can't reach
     * a local .mdb file once it's hosted on Railway).
     *
     * Returns how many of the given punches were actually processed
     * (malformed entries are skipped, not counted).
     */
    public int ingestExternalPunches(String schoolId, List<com.softschool.backend.model.DevicePunch> punches) {
        if (schoolId == null || schoolId.trim().isEmpty() || punches == null) return 0;

        int processed = 0;
        for (com.softschool.backend.model.DevicePunch p : punches) {
            if (p == null) continue;
            String badge = p.getBadgeNumber();
            String firstRaw = p.getFirstPunch();
            if (badge == null || badge.trim().isEmpty() || firstRaw == null || firstRaw.trim().isEmpty()) {
                continue;
            }
            try {
                LocalDateTime first = LocalDateTime.parse(firstRaw.trim());
                String lastRaw = p.getLastPunch();
                LocalDateTime last = (lastRaw != null && !lastRaw.trim().isEmpty())
                        ? LocalDateTime.parse(lastRaw.trim())
                        : first;
                processPunchesForSchool(schoolId, badge.trim(), p.getName(), Timestamp.valueOf(first), Timestamp.valueOf(last));
                processed++;
            } catch (Exception e) {
                System.err.println("⚠️ Biometric Sync: skipping malformed punch for badge "
                        + badge + " (" + e.getMessage() + ")");
            }
        }
        return processed;
    }

    private void processPunches(String badgeNumber, String name, Timestamp first, Timestamp last) {
        processPunchesForSchool(this.schoolId, badgeNumber, name, first, last);
    }

    private void processPunchesForSchool(String schoolId, String badgeNumber, String name, Timestamp first, Timestamp last) {
        LocalDate today = LocalDate.now();

        Optional<Staff> staffOpt = resolveStaff(badgeNumber, schoolId);
        if (staffOpt.isEmpty()) return;

        Staff s = staffOpt.get();
        // Same as above: memberId is a String keyed on staffId, not the
        // internal Long id.
        Optional<Attendance> existingAtt = attendanceRepository.findByMemberIdAndDateAndSchoolId(s.getStaffId(), today, schoolId);

        LocalTime checkInTime = first.toLocalDateTime().toLocalTime();
        LocalTime checkOutTime = last.toLocalDateTime().toLocalTime();

        if (existingAtt.isEmpty()) {
            // ONLY create a record if the person has NO record at all yet
            Attendance att = new Attendance();
            att.setSchoolId(schoolId);
            att.setMemberId(s.getStaffId());
            att.setMemberName(s.getName());
            att.setMemberType("STAFF");
            att.setRole(resolveRole(s));
            att.setDate(today);
            att.setStatus("present");
            att.setCheckIn(checkInTime);
            att.setReason("Biometric Check-In");
            attendanceRepository.save(att);
            System.out.println("☀️ Biometric Sync: " + s.getName() + " marked Present.");
        } else {
            Attendance att = existingAtt.get();

            // CRITICAL FIX:
            // If you manually changed this person to 'absent' or 'leave',
            // the Biometric machine will NO LONGER overwrite it.
            if ("absent".equalsIgnoreCase(att.getStatus()) || "leave".equalsIgnoreCase(att.getStatus())) {
                // System.out.println("⏭️ Skipping Biometric for " + s.getName() + " (Manually set to " + att.getStatus() + ")");
                return;
            }

            // Standard Check-Out logic
            if (checkOutTime.isAfter(att.getCheckIn()) && att.getCheckOut() == null) {
                att.setCheckOut(checkOutTime);
                att.setReason("Biometric Check-Out");
                attendanceRepository.save(att);
                System.out.println("🌙 Biometric Sync: " + s.getName() + " Check-Out updated.");
            }
        }
    }

    /**
     * The biometric device only ever sends a plain number as the badge
     * (e.g. "7"), but Staff.staffId carries a school-specific prefix
     * (e.g. "PSC_S_7"). This resolves one to the other automatically:
     *   1. Try an exact match first, in case a staffId is ever set up to
     *      literally equal the raw badge number.
     *   2. Otherwise, match the badge against the trailing digits of every
     *      staffId in this school (so "7" finds "PSC_S_7", "07" finds it
     *      too, etc.) — never across schools, since it's scoped by schoolId.
     * If StaffRepository already exposes an equivalent "all staff for a
     * school" method under a different name, swap it in below instead of
     * findBySchoolId.
     */
    private Optional<Staff> resolveStaff(String badgeNumber, String schoolId) {
        if (badgeNumber == null || badgeNumber.trim().isEmpty() || schoolId == null) {
            return Optional.empty();
        }

        Optional<Staff> exact = staffRepository.findByStaffIdAndSchoolId(badgeNumber, schoolId);
        if (exact.isPresent()) return exact;

        String normalizedBadge = normalizeDigits(badgeNumber);
        if (normalizedBadge == null || normalizedBadge.isEmpty()) return Optional.empty();

        List<Staff> schoolStaff = staffRepository.findBySchoolId(schoolId);
        for (Staff s : schoolStaff) {
            String suffix = trailingDigits(s.getStaffId());
            if (suffix != null && suffix.equals(normalizedBadge)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern TRAILING_DIGITS =
            java.util.regex.Pattern.compile("(\\d+)$");

    // Pulls the trailing numeric portion out of a staffId like "PSC_S_07"
    // and strips leading zeros so it can be compared against a device
    // badge number ("7" and "07" should both match staffId "...S_07").
    private String trailingDigits(String staffId) {
        if (staffId == null) return null;
        java.util.regex.Matcher m = TRAILING_DIGITS.matcher(staffId.trim());
        if (!m.find()) return null;
        return normalizeDigits(m.group(1));
    }

    private String normalizeDigits(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        String stripped = trimmed.replaceFirst("^0+(?=\\d)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    // Staff.role is a legacy field the current frontend no longer populates
    // (see Staff.java) — the field that's actually filled in now is `type`
    // ("Teaching"/"Non-Teaching"). Prefer role if it's ever set, fall back
    // to type, so Attendance.role isn't silently left blank.
    private String resolveRole(Staff s) {
        if (s.getRole() != null && !s.getRole().trim().isEmpty()) {
            return s.getRole();
        }
        return s.getType();
    }
}