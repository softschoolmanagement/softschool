package com.softschool.backend.controller;

import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.service.PlanEnforcementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/**
 * Every endpoint here is scoped by schoolId (School.schoolId, e.g. "SS_77_1")
 * so each school only ever sees and modifies its own staff — see
 * Staff.schoolId / the (schoolId, staffId) unique constraint on the entity.
 * The frontend (manage-staff.js) always sends the logged-in school's ID,
 * taken from the real session set up at login.
 */
@RestController
@RequestMapping("/api/staff")
@CrossOrigin(origins = "*")
public class StaffController {

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Staff staff) {
        if (isBlank(staff.getSchoolId())) {
            return badRequest("schoolId is required.");
        }
        if (isBlank(staff.getStaffId())) {
            return badRequest("staffId is required.");
        }

        // Handle Update logic: if staffId exists FOR THIS SCHOOL, reuse the
        // internal primary key so this is an update, not a duplicate insert —
        // scoped by schoolId too, so School A can never overwrite School B's
        // staff member just because they happen to share a staffId (e.g. same
        // prefix producing "PSC_S_1" for both).
        boolean isNew = staffRepository.findByStaffIdAndSchoolId(staff.getStaffId(), staff.getSchoolId())
                .map(existing -> {
                    staff.setId(existing.getId());
                    return false;
                })
                .orElse(true);

        // New hires count against staffLimit + the "staff" feature lock;
        // edits to an existing staff row don't change headcount, so they
        // only need the feature-lock check (Security Audit #2).
        if (isNew) {
            planEnforcementService.requireCapacityForNewStaff(staff.getSchoolId());
        } else {
            planEnforcementService.requireFeature(staff.getSchoolId(), PlanEnforcementService.FEATURE_STAFF);
        }

        Staff saved = staffRepository.save(staff);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<?> getAll(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return ResponseEntity.ok(staffRepository.findBySchoolId(schoolId));
    }

    @DeleteMapping("/{staffId}")
    public ResponseEntity<?> delete(@PathVariable String staffId, @RequestParam String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return staffRepository.findByStaffIdAndSchoolId(staffId, schoolId)
                .map(existing -> {
                    staffRepository.deleteById(existing.getId());
                    return ResponseEntity.ok().build();
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        return Collections.singletonMap("error", message);
    }
}