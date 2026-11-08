package com.softschool.backend.controller;

import com.softschool.backend.model.ParentSubmission;
import com.softschool.backend.model.School;
import com.softschool.backend.model.TeacherFeedback;
import com.softschool.backend.repository.ParentSubmissionRepository;
import com.softschool.backend.repository.SchoolRepository;
import com.softschool.backend.repository.TeacherFeedbackRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Super Admin: feedback sent from the parent portal and the teacher portal, across ALL schools.
 * Lives under /api/admin/** so AdminAuthFilter already requires the super-admin session token.
 *
 *   GET /api/admin/feedback?source=parent|teacher[&type=all|bug|suggestion]
 *
 * Parent portal: rows of ParentSubmission with kind "feedback" (the "Submit Feedback" page, about the
 * software). The parent page only sends a free-text category, so bug vs suggestion is derived from it
 * (see classify). Teacher portal: TeacherFeedback rows, which carry an explicit type.
 */
@RestController
@RequestMapping("/api/admin/feedback")
public class FeedbackAdminController {

    private static final int MAX_ROWS = 500;
    private static final Pattern BUG_WORDS = Pattern.compile(
            "bug|error|crash|issue|problem|broken|glitch|not working|doesn'?t work|does not work|fail|wrong|stuck|freez");

    @Autowired private ParentSubmissionRepository parentRepository;
    @Autowired private TeacherFeedbackRepository teacherRepository;
    @Autowired private SchoolRepository schoolRepository;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String source,
                                  @RequestParam(required = false, defaultValue = "all") String type) {
        String src = source == null ? "" : source.trim().toLowerCase();
        String wanted = type == null ? "all" : type.trim().toLowerCase();
        if (!src.equals("parent") && !src.equals("teacher")) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "source must be parent or teacher."));
        }
        if (!wanted.equals("all") && !wanted.equals("bug") && !wanted.equals("suggestion")) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "type must be all, bug or suggestion."));
        }

        Map<String, String> schoolNames = new HashMap<>();
        for (School s : schoolRepository.findAll()) schoolNames.put(s.getSchoolId(), s.getName());

        List<Map<String, Object>> items = new ArrayList<>();
        int bugs = 0, suggestions = 0;

        if (src.equals("parent")) {
            for (ParentSubmission s : parentRepository.findByKindOrderByCreatedAtDesc("feedback", PageRequest.of(0, MAX_ROWS))) {
                String t = classify(s.getCategory(), s.getTitle(), s.getBody());
                if (t.equals("bug")) bugs++; else suggestions++;
                if (!wanted.equals("all") && !wanted.equals(t)) continue;
                Map<String, Object> m = base(s.getId(), t, s.getSchoolId(), schoolNames);
                m.put("category", s.getCategory());
                m.put("title", s.getTitle());
                m.put("body", s.getBody());
                m.put("fromName", s.getGuardianName() != null ? s.getGuardianName() : s.getStudentName());
                m.put("fromDetail", s.getPhone());
                m.put("createdAt", s.getCreatedAt().toString());
                items.add(m);
            }
        } else {
            for (TeacherFeedback f : teacherRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, MAX_ROWS))) {
                String t = "bug".equals(f.getType()) ? "bug" : "suggestion";
                if (t.equals("bug")) bugs++; else suggestions++;
                if (!wanted.equals("all") && !wanted.equals(t)) continue;
                Map<String, Object> m = base(f.getId(), t, f.getSchoolId(), schoolNames);
                m.put("category", null);
                m.put("title", f.getTitle());
                m.put("body", f.getBody());
                m.put("fromName", f.getTeacherName());
                m.put("fromDetail", f.getStaffId());
                m.put("createdAt", f.getCreatedAt().toString());
                items.add(m);
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", bugs + suggestions);
        counts.put("bug", bugs);
        counts.put("suggestion", suggestions);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", src);
        out.put("counts", counts);
        out.put("items", items);
        return ResponseEntity.status(HttpStatus.OK).body(out);
    }

    private static Map<String, Object> base(Long id, String type, String schoolId, Map<String, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("schoolId", schoolId);
        m.put("schoolName", names.getOrDefault(schoolId, schoolId));
        return m;
    }

    /** Parent feedback has no explicit type: the chosen category decides, then the wording. Default = suggestion. */
    static String classify(String category, String title, String body) {
        String cat = category == null ? "" : category.toLowerCase();
        if (BUG_WORDS.matcher(cat).find()) return "bug";
        if (cat.contains("suggest") || cat.contains("idea") || cat.contains("feature") || cat.contains("improve")) return "suggestion";
        String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase();
        return BUG_WORDS.matcher(text).find() ? "bug" : "suggestion";
    }
}
