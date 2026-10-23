package com.softschool.backend.controller;

import com.softschool.backend.model.AcademicSession;
import com.softschool.backend.repository.AcademicSessionRepository;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(
            @RequestParam(required = false) String schoolId,
            @RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            return badRequest("Request body is required.");
        }

        String resolvedSchoolId = isBlank(schoolId)
                ? value(payload.get("schoolId"))
                : schoolId.trim();
        String startMonth = value(payload.get("startMonth"));
        String endMonth = value(payload.get("endMonth"));
        boolean firstSession = Boolean.TRUE.equals(payload.get("firstSession"));

        if (isBlank(resolvedSchoolId)) {
            return badRequest("schoolId is required.");
        }
        if (!isValidMonth(startMonth) || !isValidMonth(endMonth)) {
            return badRequest("startMonth and endMonth must use yyyy-MM format.");
        }

        final YearMonth start;
        final YearMonth end;
        try {
            start = YearMonth.parse(startMonth);
            end = YearMonth.parse(endMonth);
        } catch (DateTimeParseException ex) {
            return badRequest("Enter valid start and end months.");
        }
        if (end.isBefore(start)) {
            return badRequest("endMonth must be the same as or later than startMonth.");
        }

        if (academicSessionRepository
                .findBySchoolIdAndStartMonthAndEndMonth(resolvedSchoolId, startMonth, endMonth)
                .isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Collections.singletonMap("error", "This session range already exists."));
        }

        if (firstSession) {
            List<AcademicSession> existing =
                    academicSessionRepository.findBySchoolIdOrderByStartMonthAsc(resolvedSchoolId);
            for (AcademicSession session : existing) {
                if (session.isFirstSession()) {
                    session.setFirstSession(false);
                }
            }
            academicSessionRepository.saveAll(existing);
        }

        AcademicSession session = new AcademicSession();
        session.setSchoolId(resolvedSchoolId);
        session.setStartMonth(startMonth);
        session.setEndMonth(endMonth);
        session.setFirstSession(firstSession);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(academicSessionRepository.save(session));
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
}
