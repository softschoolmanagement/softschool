package com.softschool.backend.controller;

import com.softschool.backend.model.Attendance;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.service.PlanEnforcementService;
import com.softschool.backend.service.ZktecoService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/attendance")
@CrossOrigin(origins = "*")
public class AttendanceController {

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    @PostMapping("/save")
    public ResponseEntity<String> saveAttendance(@RequestBody List<Attendance> attendanceList) {
        if (attendanceList == null || attendanceList.isEmpty()) {
            return ResponseEntity.badRequest().body("No records provided");
        }
        // Every record MUST carry its schoolId, or it becomes impossible to
        // tell which school it belongs to once saved (and can collide with
        // another school's data on the same memberId/date).
        for (Attendance a : attendanceList) {
            if (a.getSchoolId() == null || a.getSchoolId().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body("schoolId is required on every attendance record (missing for memberId="
                                + a.getMemberId() + ")");
            }
        }

        // All records in one call belong to the same school in practice
        // (the frontend only ever submits one school's sheet at a time);
        // enforce the "attendance" feature lock once up front (Security
        // Audit #2 — this was previously only hidden client-side).
        planEnforcementService.requireFeature(attendanceList.get(0).getSchoolId(), PlanEnforcementService.FEATURE_ATTENDANCE);

        // UPSERT instead of blind insert.
        //
        // Previously this endpoint always did attendanceRepository.saveAll(attendanceList)
        // with no id set, so every call — including re-saving the SAME day for the
        // SAME person — created a brand new row. That's what caused attendance to
        // "reset" after a reload: the frontend cache that remembered who was already
        // marked lived only in memory, so after a reload it re-sent the sheet and the
        // backend happily inserted a second (third, fourth...) row per person per day.
        // Those duplicates are also exactly why the "marked students/staff" counts on
        // the main dashboard and on this page kept drifting — they're computed by
        // counting rows, and the row count no longer matched the number of people.
        //
        // Now: one attendance row per (memberId, date, schoolId). If a row already
        // exists we update it in place; otherwise we insert a new one.
        List<Attendance> toPersist = new ArrayList<>();
        for (Attendance incoming : attendanceList) {
            if (incoming.getDate() == null || incoming.getMemberId() == null) {
                continue; // guard against malformed rows rather than corrupting data
            }
            Optional<Attendance> existingOpt = attendanceRepository.findByMemberIdAndDateAndSchoolId(
                    incoming.getMemberId(), incoming.getDate(), incoming.getSchoolId());

            if (existingOpt.isPresent()) {
                Attendance existing = existingOpt.get();
                existing.setMemberName(incoming.getMemberName());
                existing.setMemberType(incoming.getMemberType());
                existing.setClassName(incoming.getClassName());
                existing.setSection(incoming.getSection());
                existing.setRole(incoming.getRole());
                existing.setStatus(incoming.getStatus());
                existing.setReason(incoming.getReason());
                if (incoming.getCapturedPhoto() != null && !incoming.getCapturedPhoto().isEmpty()) {
                    existing.setCapturedPhoto(incoming.getCapturedPhoto());
                }
                // A manual save (from the attendance sheet) never carries a
                // check-in/out time. Don't let it wipe out a real biometric
                // check-in/out that's already stored — only overwrite when the
                // incoming record actually supplies a new value.
                if (incoming.getCheckIn() != null) existing.setCheckIn(incoming.getCheckIn());
                if (incoming.getCheckOut() != null) existing.setCheckOut(incoming.getCheckOut());
                toPersist.add(existing);
            } else {
                toPersist.add(incoming);
            }
        }

        attendanceRepository.saveAll(toPersist);
        return ResponseEntity.ok("Saved " + toPersist.size() + " records successfully!");
    }

    // Inside AttendanceController.java
@Autowired
private ZktecoService zktecoService;

@GetMapping("/test-biometric")
public String testBiometric(@RequestParam String id) {
    // Fix for Error 2: We must pass a LocalTime. 
    // We use LocalTime.now() to pretend the scan is happening exactly now.
    zktecoService.markAttendance(id, "Test User", java.time.LocalTime.now()); 
    
    return "Fingerprint process triggered for ID: " + id;
}

// Dashboard summary for one day: how many students/staff are marked
// "present" so far, scoped to ONE school. Used by main.js (main page's
// "Today's Attendance" widget) via GET /api/attendance?date=...&schoolId=...
// This endpoint didn't exist before, so that widget's fetch always 404'd
// and silently fell back to zero — the counts you saw were never actually
// backed by real data.
@GetMapping
public ResponseEntity<?> getAttendanceSummary(@RequestParam String date, @RequestParam String schoolId) {
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    List<Attendance> records = attendanceRepository.findByDateAndSchoolId(LocalDate.parse(date), schoolId);

    long presentStudents = records.stream()
            .filter(a -> "STUDENT".equalsIgnoreCase(a.getMemberType()))
            .filter(a -> "present".equalsIgnoreCase(a.getStatus()))
            .count();
    // Staff count as present either because they were marked present manually
    // or because the biometric device recorded a check-in.
    long presentStaff = records.stream()
            .filter(a -> "STAFF".equalsIgnoreCase(a.getMemberType()))
            .filter(a -> "present".equalsIgnoreCase(a.getStatus()) || a.getCheckIn() != null)
            .count();

    Map<String, Object> summary = new HashMap<>();
    summary.put("presentStudents", presentStudents);
    summary.put("presentStaff", presentStaff);
    summary.put("hasData", !records.isEmpty());
    return ResponseEntity.ok(summary);
}

// Marked students for one school on one day — mirrors /staff below, but
// for memberType STUDENT. Optionally narrowed to a single class. This was
// completely missing before, which meant the attendance page had no way to
// ask the backend "who's already been marked today?" for students — it could
// only rely on an in-memory cache that vanished on every page reload.
@GetMapping("/students")
public ResponseEntity<?> getStudentAttendance(
        @RequestParam String date,
        @RequestParam String schoolId,
        @RequestParam(required = false) String className) {
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    LocalDate parsedDate = LocalDate.parse(date);
    if (className != null && !className.trim().isEmpty()) {
        return ResponseEntity.ok(attendanceRepository.findByMemberTypeAndDateAndSchoolIdAndClassName(
                "STUDENT", parsedDate, schoolId, className));
    }
    return ResponseEntity.ok(
            attendanceRepository.findByMemberTypeAndDateAndSchoolId("STUDENT", parsedDate, schoolId));
}

@GetMapping("/staff")
public ResponseEntity<?> getStaffAttendance(@RequestParam String date, @RequestParam String schoolId) {
    // This returns all marked staff for today (biometric or manual), for ONE school only.
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    return ResponseEntity.ok(
            attendanceRepository.findByMemberTypeAndDateAndSchoolId("STAFF", LocalDate.parse(date), schoolId));
}

// Full attendance history for one member (staff or student), scoped to
// their school so the frontend's "History" view never shows another
// school's records even if the memberId string happens to match.
@GetMapping("/history/{memberId}")
public ResponseEntity<?> getMemberHistory(@PathVariable String memberId, @RequestParam String schoolId) {
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    return ResponseEntity.ok(
            attendanceRepository.findByMemberIdAndSchoolIdOrderByDateDesc(memberId, schoolId));
}
}
