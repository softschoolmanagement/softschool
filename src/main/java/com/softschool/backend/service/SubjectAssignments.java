package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Staff;

/**
 * Helpers for Staff.subjectAssignments — a JSON array of { subject, cls, section }
 * saying which subject a teacher teaches in which class.
 */
public final class SubjectAssignments {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubjectAssignments() {}

    /** The parsed array, or null when the teacher has none on file (older records). */
    private static JsonNode parse(Staff s) {
        String json = s == null ? null : s.getSubjectAssignments();
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode arr = MAPPER.readTree(json);
            return (arr.isArray() && arr.size() > 0) ? arr : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasAssignments(Staff s) {
        return parse(s) != null;
    }

    /**
     * Whether the teacher teaches {@code subject} in the class named by {@code classLabel}
     * ("Class 5 - A" or "Class 5"). Teachers without any subject/class pairs keep the old
     * behaviour and are not blocked here (callers still run their own class/subject checks).
     */
    public static boolean teachesSubjectInClass(Staff s, String subject, String classLabel) {
        JsonNode arr = parse(s);
        if (arr == null) return true;
        if (subject == null || classLabel == null) return false;
        String[] parts = classLabel.trim().split("\\s+-\\s+", 2);
        String cls = parts[0].trim(), sec = parts.length > 1 ? parts[1].trim() : "";
        for (JsonNode n : arr) {
            String sub = n.path("subject").asText("").trim();
            String c = n.path("cls").asText("").trim();
            String sc = n.path("section").asText("").trim();
            if (sub.equalsIgnoreCase(subject.trim()) && c.equalsIgnoreCase(cls)
                    && (sc.isEmpty() || sec.isEmpty() || sc.equalsIgnoreCase(sec))) {
                return true;
            }
        }
        return false;
    }

    /** Null when valid; otherwise a message. Every entry needs both a subject and a class. */
    public static String validate(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > 20000) return "Too many subject assignments.";
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return "Subject assignments are invalid.";
            for (JsonNode n : arr) {
                String sub = n.path("subject").asText("").trim();
                String cls = n.path("cls").asText("").trim();
                if (sub.isEmpty()) return "Every class assignment needs a subject.";
                if (cls.isEmpty()) return "Subject \"" + sub + "\" needs a class. Choose the class this teacher teaches it in.";
            }
            return null;
        } catch (Exception e) {
            return "Subject assignments are invalid.";
        }
    }
}
