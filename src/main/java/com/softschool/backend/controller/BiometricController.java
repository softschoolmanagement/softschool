package com.softschool.backend.controller;

import com.softschool.backend.model.BiometricPathRequest;
import com.softschool.backend.service.PlanEnforcementService;
import com.softschool.backend.service.ZktecoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/biometric")
@CrossOrigin(origins = "*") // This allows the frontend to talk to the backend
public class BiometricController {

    @Autowired
    private ZktecoService zktecoService;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    @PostMapping("/link")
    public ResponseEntity<String> linkDevice(@RequestBody BiometricPathRequest request) {
        if (request.getPath() == null || request.getPath().isEmpty()) {
            return ResponseEntity.badRequest().body("Path is empty");
        }
        // NEW: schoolId is required now too — without it the service has no
        // way to scope punches to the right school's staff (schoolId+staffId).
        if (request.getSchoolId() == null || request.getSchoolId().isEmpty()) {
            return ResponseEntity.badRequest().body("schoolId is required");
        }

        // Biometric is a lockable, plan-gated feature (basic/pro plans lock
        // it by default in access-control.js) — previously that lock only
        // hid the UI button, so any client could still POST here directly
        // and enable biometric on a plan that shouldn't have it (Security
        // Audit #2). Enforce it server-side too.
        planEnforcementService.requireFeature(request.getSchoolId(), PlanEnforcementService.FEATURE_BIOMETRIC);

        // Pass both the path and the school to our service
        zktecoService.updateMdbPath(request.getPath(), request.getSchoolId());

        return ResponseEntity.ok("Path updated successfully");
    }
}