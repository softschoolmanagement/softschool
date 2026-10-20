package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.dto.ParentTeacherDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Locale;

/** Small static helpers shared by the parent-portal controllers. */
public final class ParentUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParentUtil() { }

    // ── phone numbers ─────────────────────────────────────────────

    /** Last 10 digits of a phone number, or null when there are fewer than 10 digits. */
    public static String tail10(String raw) {
        if (raw == null) return null;
        String digits = digitsOnly(raw);
        return digits.length() < 10 ? null : digits.substring(digits.length() - 10);
    }

    public static String digitsOnly(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    public static boolean phoneMatches(String stored, String tail10) {
        String d = digitsOnly(stored);
        return tail10 != null && d.length() >= 10 && d.endsWith(tail10);
    }

    // ── classes ─────────────────────────────────────────────────

    /** "Class 5 - B" (or just "Class 5" when the student has no section). Same rule as MessageController. */
    public static String classLabel(ParentChildDTO c) {
        String cls = c.getStudentClass() == null ? "" : c.getStudentClass().trim();
        String sec = c.getSection() == null ? "" : c.getSection().trim();
        return sec.isEmpty() ? cls : cls + " - " + sec;
    }

    /** True when {@code candidate} names this child's class, with or without the section. */
    public static boolean classMatches(String candidate, ParentChildDTO c) {
        if (candidate == null || candidate.isBlank()) return false;
        String x = candidate.trim();
        String cls = c.getStudentClass() == null ? "" : c.getStudentClass().trim();
        return x.equalsIgnoreCase(classLabel(c)) || (!cls.isEmpty() && x.equalsIgnoreCase(cls));
    }

    /** True when the class label (e.g. "Class 5 - A") is one the teacher teaches or is incharge of. */
    public static boolean teachesClass(ParentTeacherDTO s, String label) {
        if (label == null || label.isBlank()) return false;
        String[] parts = label.split("\\s+-\\s+", 2);
        String cls = parts[0].trim(), sec = parts.length > 1 ? parts[1].trim() : "";
        for (String json : new String[]{s.getClassAssignments(), s.getInchargeAssignments()}) {
            try {
                if (json == null || json.isBlank()) continue;
                JsonNode arr = MAPPER.readTree(json);
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

    // ── attendance ─────────────────────────────────────────────

    /** Maps whatever the attendance sheet stored ("Present", "ABSENT", "On Leave", "Late"...) to the portal's four values. */
    public static String normalizeAttendance(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("abs")) return "absent";
        if (s.contains("leave")) return "leave";
        if (s.contains("holiday")) return "holiday";
        if (s.contains("pres") || s.contains("late")) return "present";
        return s;
    }

    // ── text / responses ───────────────────────────────────────

    public static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    public static double round2(Double d) {
        return d == null ? 0.0 : Math.round(d * 100.0) / 100.0;
    }

    public static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
