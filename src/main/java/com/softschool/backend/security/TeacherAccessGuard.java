package com.softschool.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.StaffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Limits what a TEACHER session token may do.
 *
 * Teacher tokens are the normal school-scoped tokens, but their username is
 * "teacher:<staffId>" (issued by SchoolAuthController#teacherLogin). Without
 * this guard such a token would be as powerful as the school admin's: it
 * could read salaries, finance, every staff record and edit anything.
 *
 * A teacher token may ONLY:
 *   - GET  /api/students/summary        (to list her incharge class)
 *   - GET  /api/attendance/students     (to see today's saved marks)
 *   - POST /api/attendance/save         (student records, today only, and only
 *                                        for the class/section she is incharge of)
 * Everything else is denied. Called from SchoolAuthFilter.
 */
@Component
public class TeacherAccessGuard {

    public static final String PREFIX = "teacher:";

    @Autowired
    private StaffRepository staffRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    public static boolean isTeacher(SchoolSessionService.Principal p) {
        return p != null && p.getUsername() != null && p.getUsername().startsWith(PREFIX);
    }

    /** Returns null when the request is allowed, otherwise a message explaining the denial. */
    public String violation(SchoolSessionService.Principal p, String method, String uri, String body) {
        boolean get = "GET".equalsIgnoreCase(method);
        boolean post = "POST".equalsIgnoreCase(method);
        if (get && ("/api/students/summary".equals(uri) || "/api/attendance/students".equals(uri))) {
            return null;
        }
        if (post && "/api/attendance/save".equals(uri)) {
            return checkSave(p.getSchoolId(), p.getUsername().substring(PREFIX.length()), body);
        }
        return "Teacher accounts cannot access this resource.";
    }

    private String checkSave(String schoolId, String staffId, String body) {
        if (body == null || body.isBlank()) return "Attendance data is required.";

        Staff staff = staffRepository.findByStaffIdAndSchoolId(staffId, schoolId).orElse(null);
        if (staff == null || !"Teaching".equalsIgnoreCase(staff.getType())) {
            return "This teacher account is no longer active.";
        }
        List<String[]> incharge = inchargeOf(staff);
        if (incharge.isEmpty()) return "Only a class incharge can mark attendance.";

        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray() || root.size() == 0) return "Attendance data is required.";

            LocalDate today = LocalDate.now();
            for (JsonNode rec : root) {
                if (!"STUDENT".equalsIgnoreCase(rec.path("memberType").asText(""))) {
                    return "Teachers can only mark student attendance.";
                }
                if (!schoolId.equals(rec.path("schoolId").asText(""))) {
                    return "The request does not match the authenticated school.";
                }
                // +/- 1 day tolerance covers the server running in a different time zone.
                LocalDate date = LocalDate.parse(rec.path("date").asText(""));
                if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(1))) {
                    return "Teachers can only mark today's attendance.";
                }
                String cls = rec.path("className").asText("");
                String sec = rec.path("section").asText("");
                boolean allowed = false;
                for (String[] a : incharge) {
                    if (a[0].equalsIgnoreCase(cls.trim()) && (a[1].isEmpty() || a[1].equalsIgnoreCase(sec.trim()))) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) return "You can only mark attendance for the class you are incharge of.";
            }
            return null;
        } catch (Exception e) {
            return "Invalid attendance data.";
        }
    }

    /** Same rule the frontend uses: inchargeAssignments JSON, else assignedClass when flagged incharge. */
    private List<String[]> inchargeOf(Staff s) {
        List<String[]> out = new ArrayList<>();
        try {
            if (s.getInchargeAssignments() != null && !s.getInchargeAssignments().isBlank()) {
                JsonNode arr = mapper.readTree(s.getInchargeAssignments());
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String cls = n.path("cls").asText("").trim();
                        if (!cls.isEmpty()) out.add(new String[]{cls, n.path("section").asText("").trim()});
                    }
                }
            }
        } catch (Exception ignored) { /* fall through to legacy fields */ }
        if (out.isEmpty() && s.getAssignedClass() != null && !s.getAssignedClass().isBlank()
                && (Boolean.TRUE.equals(s.getIsClassIncharge())
                    || (s.getIncharge() != null && !s.getIncharge().isBlank()))) {
            out.add(new String[]{s.getAssignedClass().trim(),
                    s.getAssignedSection() == null ? "" : s.getAssignedSection().trim()});
        }
        return out;
    }
}
