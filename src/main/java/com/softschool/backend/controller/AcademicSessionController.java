package com.softschool.backend.controller;

import com.softschool.backend.model.AcademicSession;
import com.softschool.backend.repository.AcademicSessionRepository;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class AcademicSessionController {

    @Autowired
    private AcademicSessionRepository academicSessionRepository;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        List<AcademicSession> sessions =
                academicSessionRepository.findBySchoolIdOrderByStartMonthAsc(schoolId);
        return ResponseEntity.ok(sessions);
    }

    /** The session the school marked as current. Other pages can use this to scope their data. */
    @GetMapping("/current")
    public ResponseEntity<?> current(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        Optional<AcademicSession> current =
                academicSessionRepository.findFirstBySchoolIdAndCurrentSessionTrue(schoolId.trim());
        if (current.isPresent()) {
            return ResponseEntity.ok(current.get());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Collections.singletonMap("error", "No current session has been set."));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(
            @RequestParam(required = false) String schoolId,
            @RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            return badRequest("Request body is required.");
        }

        String resolvedSchoolId = resolveSchoolId(schoolId, payload);
        String startMonth = value(payload.get("startMonth"));
        String endMonth = value(payload.get("endMonth"));
        boolean firstSession = Boolean.TRUE.equals(payload.get("firstSession"));

        if (isBlank(resolvedSchoolId)) {
            return badRequest("schoolId is required.");
        }
        String rangeError = validateRange(startMonth, endMonth);
        if (rangeError != null) {
            return badRequest(rangeError);
        }

        if (academicSessionRepository
                .findBySchoolIdAndStartMonthAndEndMonth(resolvedSchoolId, startMonth, endMonth)
                .isPresent()) {
            return duplicateRange();
        }

        if (firstSession) {
            clearFirstSession(resolvedSchoolId);
        }

        AcademicSession session = new AcademicSession();
        session.setSchoolId(resolvedSchoolId);
        session.setStartMonth(startMonth);
        session.setEndMonth(endMonth);
        session.setFirstSession(firstSession);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(academicSessionRepository.save(session));
    }

    /** Changes the start / end month (and optionally the first-session flag) of a session. */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestParam(required = false) String schoolId,
            @RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            return badRequest("Request body is required.");
        }

        String resolvedSchoolId = resolveSchoolId(schoolId, payload);
        if (isBlank(resolvedSchoolId)) {
            return badRequest("schoolId is required.");
        }

        Optional<AcademicSession> found =
                academicSessionRepository.findByIdAndSchoolId(id, resolvedSchoolId);
        if (!found.isPresent()) {
            return notFound();
        }

        String startMonth = value(payload.get("startMonth"));
        String endMonth = value(payload.get("endMonth"));
        String rangeError = validateRange(startMonth, endMonth);
        if (rangeError != null) {
            return badRequest(rangeError);
        }

        Optional<AcademicSession> sameRange = academicSessionRepository
                .findBySchoolIdAndStartMonthAndEndMonth(resolvedSchoolId, startMonth, endMonth);
        if (sameRange.isPresent() && !sameRange.get().getId().equals(id)) {
            return duplicateRange();
        }

        AcademicSession session = found.get();
        if (payload.containsKey("firstSession")) {
            boolean firstSession = Boolean.TRUE.equals(payload.get("firstSession"));
            if (firstSession && !session.isFirstSession()) {
                clearFirstSession(resolvedSchoolId);
            }
            session.setFirstSession(firstSession);
        }
        session.setStartMonth(startMonth);
        session.setEndMonth(endMonth);

        return ResponseEntity.ok(academicSessionRepository.save(session));
    }

    /** Marks one session as the school's current session and clears the flag on the others. */
    @PutMapping("/{id}/current")
    @Transactional
    public ResponseEntity<?> makeCurrent(
            @PathVariable Long id,
            @RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        String resolvedSchoolId = schoolId.trim();

        Optional<AcademicSession> found =
                academicSessionRepository.findByIdAndSchoolId(id, resolvedSchoolId);
        if (!found.isPresent()) {
            return notFound();
        }

        List<AcademicSession> all =
                academicSessionRepository.findBySchoolIdOrderByStartMonthAsc(resolvedSchoolId);
        for (AcademicSession session : all) {
            session.setCurrentSession(session.getId().equals(id));
        }
        academicSessionRepository.saveAll(all);

        return ResponseEntity.ok(found.get());
    }

    /**
     * Deletes the session record only. Students, staff and fees are not stored per session
     * (they are matched to a session by date), so none of that data is touched.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }

        Optional<AcademicSession> found =
                academicSessionRepository.findByIdAndSchoolId(id, schoolId.trim());
        if (!found.isPresent()) {
            return notFound();
        }

        academicSessionRepository.delete(found.get());
        return ResponseEntity.noContent().build();
    }

    private void clearFirstSession(String schoolId) {
        List<AcademicSession> existing =
                academicSessionRepository.findBySchoolIdOrderByStartMonthAsc(schoolId);
        for (AcademicSession session : existing) {
            if (session.isFirstSession()) {
                session.setFirstSession(false);
            }
        }
        academicSessionRepository.saveAll(existing);
    }

    /** Returns an error message, or null when both months are valid and in order. */
    private static String validateRange(String startMonth, String endMonth) {
        if (!isValidMonth(startMonth) || !isValidMonth(endMonth)) {
            return "startMonth and endMonth must use yyyy-MM format.";
        }
        final YearMonth start;
        final YearMonth end;
        try {
            start = YearMonth.parse(startMonth);
            end = YearMonth.parse(endMonth);
        } catch (DateTimeParseException ex) {
            return "Enter valid start and end months.";
        }
        if (end.isBefore(start)) {
            return "endMonth must be the same as or later than startMonth.";
        }
        return null;
    }

    private static String resolveSchoolId(String schoolId, Map<String, Object> payload) {
        return isBlank(schoolId) ? value(payload.get("schoolId")) : schoolId.trim();
    }

    private static boolean isValidMonth(String value) {
        return value != null && value.matches("\\d{4}-(0[1-9]|1[0-2])");
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Collections.singletonMap("error", message));
    }

    private static ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Collections.singletonMap("error", "Session not found."));
    }

    private static ResponseEntity<Map<String, String>> duplicateRange() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Collections.singletonMap("error", "This session range already exists."));
    }
}
