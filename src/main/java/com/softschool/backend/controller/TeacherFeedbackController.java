package com.softschool.backend.controller;

import com.softschool.backend.model.Staff;
import com.softschool.backend.model.TeacherFeedback;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.repository.TeacherFeedbackRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.security.TeacherAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Teacher portal -> Super Admin feedback.
 *
 *   POST /api/teacher-feedback   (JSON)   { "type": "bug" | "suggestion", "title": "...", "body": "..." }
 *
 * This path is NOT in SchoolAuthFilter's protected list, so the teacher session token is verified here:
 * it must be a valid school session whose username is "teacher:<staffId>" (see TeacherAccessGuard).
 * The school and the teacher always come from the token, never from the request body.
 */
@RestController
@RequestMapping("/api/teacher-feedback")
public class TeacherFeedbackController {

    private static final int DAILY_LIMIT = 10;

    @Autowired private SchoolSessionService sessionService;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TeacherFeedbackRepository feedbackRepository;

    public static class FeedbackRequest {
        public String type;
        public String title;
        public String body;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody FeedbackRequest req, HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        SchoolSessionService.Principal p = sessionService.verifyToken(token);
        if (p == null) return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        if (!TeacherAccessGuard.isTeacher(p)) return error(HttpStatus.FORBIDDEN, "Only teachers can send feedback here.");

        String staffId = p.getUsername().substring(TeacherAccessGuard.PREFIX.length());
        Staff staff = staffRepository.findByStaffIdAndSchoolId(staffId, p.getSchoolId()).orElse(null);
        if (staff == null) return error(HttpStatus.FORBIDDEN, "This teacher account is no longer active.");

        String type = req.type == null ? "" : req.type.trim().toLowerCase();
        if (!type.equals("bug") && !type.equals("suggestion")) {
            return error(HttpStatus.BAD_REQUEST, "type must be bug or suggestion.");
        }
        String body = req.body == null ? "" : req.body.trim();
        if (body.length() < 10) return error(HttpStatus.BAD_REQUEST, "Please write a little more (at least 10 characters).");
        if (body.length() > 2000) body = body.substring(0, 2000);
        String title = req.title == null ? null : req.title.trim();
        if (title != null && title.isEmpty()) title = null;
        if (title != null && title.length() > 200) title = title.substring(0, 200);

        long today = feedbackRepository.countBySchoolIdAndStaffIdAndCreatedAtAfter(
                p.getSchoolId(), staffId, Instant.now().minus(1, ChronoUnit.DAYS));
        if (today >= DAILY_LIMIT) return error(HttpStatus.TOO_MANY_REQUESTS, "Daily limit reached. Try again tomorrow.");

        TeacherFeedback f = new TeacherFeedback();
        f.setSchoolId(p.getSchoolId());
        f.setStaffId(staffId);
        f.setTeacherName(staff.getName());
        f.setType(type);
        f.setTitle(title);
        f.setBody(body);
        feedbackRepository.save(f);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", f.getId());
        out.put("type", f.getType());
        out.put("createdAt", f.getCreatedAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
