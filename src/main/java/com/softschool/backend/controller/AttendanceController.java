package com.softschool.backend.controller;

import com.softschool.backend.model.Attendance;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.service.DataCacheService;
import com.softschool.backend.service.DataCacheService.Dep;
import com.softschool.backend.service.PlanEnforcementService;
import com.softschool.backend.service.StudentCacheService;
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
public class AttendanceController {

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    @Autowired
    private com.softschool.backend.service.ParentNoticeService parentNoticeService;

    // PERFORMANCE: the read endpoints below are polled by the dashboard / attendance / reports pages.
    // Their finished JSON is cached per school and rebuilt only after an attendance row of that school
    // changes (DataCacheInvalidationListener), so repeated polls cost no MySQL query and no serialisation.
    @Autowired
    private DataCacheService dataCache;

    @Autowired
    private StudentCacheService studentCache;

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

        // Settings > Attendance Setting > "Send daily absent notice to parents": tell the parent of every
        // student marked absent today. Does nothing while that setting is off and never fails the save.
        parentNoticeService.syncAbsentAlerts(attendanceList.get(0).getSchoolId(), toPersist);

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

// BUGFIX — "Reports & Analytics' Attendance Trend graph and Avg Attendance
// figure never show anything": Reports & Analytics needs a whole year's
// worth of raw attendance rows at once (to bucket them into 12 monthly
// present/total percentages), so it called plain GET /api/attendance with
// only schoolId. But the ONLY GET mapped to that exact path is the
// single-day summary below, which requires `date` as well — so that call
// always came back 400 Bad Request, reports.js silently treated it as "no
// data" (see _reportsGet's catch), and the chart/stat pill stayed empty
// forever, even on schools with attendance marked every day. This endpoint
// returns every row for the school, across every date, so Reports can
// build its own per-month breakdown the same way the other trend charts do.
@GetMapping("/all")
public ResponseEntity<?> getAllAttendance(
        @RequestParam String schoolId,
        @RequestHeader(value = "Accept-Encoding", required = false) String acceptEncoding) {
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    // Cached as finished, gzipped JSON (bounded by bytes): this is the whole year's rows for the school.
    return studentCache.toResponse(
            dataCache.getJson(schoolId, "att-all", () -> attendanceRepository.findBySchoolId(schoolId), Dep.ATTENDANCE),
            acceptEncoding);
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
    final LocalDate day = LocalDate.parse(date);
    Map<String, Object> summary = dataCache.get(schoolId, "att-summary|" + day, 30,
            () -> buildAttendanceSummary(day, schoolId), Dep.ATTENDANCE);
    return ResponseEntity.ok(summary);
}

private Map<String, Object> buildAttendanceSummary(LocalDate day, String schoolId) {
    List<Attendance> records = attendanceRepository.findByDateAndSchoolId(day, schoolId);

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
    return summary;
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
        @RequestParam(required = false) String className,
        @RequestHeader(value = "Accept-Encoding", required = false) String acceptEncoding) {
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    final LocalDate parsedDate = LocalDate.parse(date);
    final boolean byClass = className != null && !className.trim().isEmpty();
    return studentCache.toResponse(
            dataCache.getJson(schoolId, "att-students|" + parsedDate + "|" + (byClass ? className : ""),
                    () -> byClass
                            ? attendanceRepository.findByMemberTypeAndDateAndSchoolIdAndClassName(
                                    "STUDENT", parsedDate, schoolId, className)
                            : attendanceRepository.findByMemberTypeAndDateAndSchoolId(
                                    "STUDENT", parsedDate, schoolId),
                    Dep.ATTENDANCE),
            acceptEncoding);
}

@GetMapping("/staff")
public ResponseEntity<?> getStaffAttendance(
        @RequestParam String date,
        @RequestParam String schoolId,
        @RequestHeader(value = "Accept-Encoding", required = false) String acceptEncoding) {
    // This returns all marked staff for today (biometric or manual), for ONE school only.
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    final LocalDate parsedDate = LocalDate.parse(date);
    return studentCache.toResponse(
            dataCache.getJson(schoolId, "att-staff|" + parsedDate,
                    () -> attendanceRepository.findByMemberTypeAndDateAndSchoolId("STAFF", parsedDate, schoolId),
                    Dep.ATTENDANCE),
            acceptEncoding);
}

// Full attendance history for one member (staff or student), scoped to
// their school so the frontend's "History" view never shows another
// school's records even if the memberId string happens to match.
@GetMapping("/history/{memberId}")
public ResponseEntity<?> getMemberHistory(
        @PathVariable String memberId,
        @RequestParam String schoolId,
        @RequestHeader(value = "Accept-Encoding", required = false) String acceptEncoding) {
    if (schoolId == null || schoolId.trim().isEmpty()) {
        return ResponseEntity.badRequest().body("schoolId is required");
    }
    return studentCache.toResponse(
            dataCache.getJson(schoolId, "att-history|" + memberId,
                    () -> attendanceRepository.findByMemberIdAndSchoolIdOrderByDateDesc(memberId, schoolId),
                    Dep.ATTENDANCE),
            acceptEncoding);
}
}