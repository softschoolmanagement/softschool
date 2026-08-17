package com.softschool.backend.controller;

import com.softschool.backend.model.BiometricPathRequest;
import com.softschool.backend.model.BiometricSyncRequest;
import com.softschool.backend.service.PlanEnforcementService;
import com.softschool.backend.service.ZktecoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/biometric")
@CrossOrigin(origins = "*") // This allows the frontend to talk to the backend
public class BiometricController {

    @Autowired
    private ZktecoService zktecoService;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    // Shared secret the local zkteco_sync_agent.py script must send as the
    // "X-Biometric-Key" header on every /api/biometric/sync call. Set via
    // the BIOMETRIC_SYNC_KEY env var (see .env). Left unset, the endpoint
    // fails closed (503) instead of silently accepting unauthenticated
    // attendance data from the internet — this endpoint is reachable from
    // outside the school's network now that the backend lives on Railway,
    // unlike the old in-process .mdb read.
    @Value("${biometric.sync-key:}")
    private String syncKey;

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

    // Called by the local zkteco_sync_agent.py script running on the
    // school's own machine (where the ZKTeco Time software's database
    // actually lives). Replaces the old design where the backend itself
    // opened the .mdb file directly — that only worked when the backend
    // ran on the same machine/network, which broke once it moved to
    // Railway. The script reads today's punches locally and POSTs them
    // here on a timer; this endpoint applies them with the exact same
    // logic (badge resolution, absent/leave protection, check-in/out)
    // that the old in-process scheduler used.
    @PostMapping("/sync")
    public ResponseEntity<?> syncPunches(
            @RequestHeader(value = "X-Biometric-Key", required = false) String providedKey,
            @RequestBody BiometricSyncRequest request) {

        if (syncKey == null || syncKey.isBlank()) {
            return ResponseEntity.status(503)
                    .body("Biometric sync is not configured on the server (set BIOMETRIC_SYNC_KEY).");
        }
        if (!constantTimeEquals(providedKey, syncKey)) {
            return ResponseEntity.status(401).body("Invalid or missing X-Biometric-Key header");
        }
        if (request == null || request.getSchoolId() == null || request.getSchoolId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("schoolId is required");
        }

        // Same server-side plan gate as /link (Security Audit #2).
        planEnforcementService.requireFeature(request.getSchoolId(), PlanEnforcementService.FEATURE_BIOMETRIC);

        if (request.getPunches() == null || request.getPunches().isEmpty()) {
            return ResponseEntity.ok("No punches to process");
        }

        int processed = zktecoService.ingestExternalPunches(request.getSchoolId(), request.getPunches());
        return ResponseEntity.ok("Processed " + processed + " punch record(s)");
    }

    private boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) return false;
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}