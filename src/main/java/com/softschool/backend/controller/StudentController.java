package com.softschool.backend.controller;

import com.softschool.backend.model.Student;
import com.softschool.backend.repository.StudentRepository;
import com.softschool.backend.service.PlanEnforcementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/students")
@CrossOrigin(origins = "*")
public class StudentController {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    @PostMapping({"", "/"})
    public ResponseEntity<?> saveStudent(@RequestBody(required = false) Student incoming) {
        if (incoming == null) {
            return badRequest("Request body is required.");
        }
        return persistStudent(incoming);
    }

    /**
     * REST-compatible update alias. The page currently uses POST for both
     * create and edit, but this route makes edits work for clients that use
     * PUT /api/students/{regNo}?schoolId=....
     */
    @PutMapping("/{regNo}")
    public ResponseEntity<?> updateStudent(
            @PathVariable String regNo,
            @RequestParam(required = false) String schoolId,
            @RequestBody(required = false) Student incoming) {
        if (incoming == null) {
            return badRequest("Request body is required.");
        }
        if (isBlank(incoming.getRegNo())) {
            incoming.setRegNo(regNo);
        }
        if (isBlank(incoming.getSchoolId()) && !isBlank(schoolId)) {
            incoming.setSchoolId(schoolId);
        }
        return persistStudent(incoming);
    }

    private ResponseEntity<?> persistStudent(Student incoming) {
        if (isBlank(incoming.getSchoolId())) {
            return badRequest("schoolId is required.");
        }

        // FIX: for an update, load the ROW THAT ALREADY EXISTS in MySQL and
        // copy the incoming fields onto it, instead of saving the raw request
        // body as a brand-new entity. Saving the request body directly wiped
        // out any column the frontend didn't happen to send in that request
        // (this is what was silently deleting student.photo on every edit).
        Student toSave;
        if (incoming.getRegNo() != null) {
            Student existing = studentRepository
                    .findByRegNoAndSchoolId(incoming.getRegNo(), incoming.getSchoolId())
                    .orElse(null);

            if (existing != null) {
                // Editing an existing student doesn't change headcount, so
                // only the feature lock applies here — not the seat limit.
                planEnforcementService.requireFeature(incoming.getSchoolId(), PlanEnforcementService.FEATURE_STUDENTS);
                toSave = existing;                     // the managed row already in the DB
                copyUpdatableFields(incoming, toSave);  // overlay only the fields the form sent
            } else {
                // Genuinely new student: enforce both the "students" feature
                // lock and the plan's studentLimit (Security Audit #2 — this
                // was previously only checked in the browser).
                planEnforcementService.requireCapacityForNewStudent(incoming.getSchoolId());
                toSave = incoming;
            }
        } else {
            planEnforcementService.requireCapacityForNewStudent(incoming.getSchoolId());
            toSave = incoming;
        }

        if (toSave.getStatus() == null) {
            toSave.setStatus("active");
        }

        Student saved = studentRepository.save(toSave);
        return ResponseEntity.ok(saved);
    }

    /**
     * Copy every field from the incoming request onto the existing managed
     * entity, EXCEPT: never overwrite photo/certData with a blank value.
     * That's the actual safety net — if a future request from the frontend
     * ever forgets to include the photo, the previously saved photo survives
     * instead of being nulled out.
     */
    private void copyUpdatableFields(Student in, Student existing) {
        existing.setFullName(in.getFullName());
        existing.setRollNo(in.getRollNo());
        existing.setStudentClass(in.getStudentClass());
        existing.setSection(in.getSection());
        existing.setAdmissionDate(in.getAdmissionDate());
        existing.setGender(in.getGender());
        existing.setDob(in.getDob());
        existing.setAge(in.getAge());
        existing.setStudentBform(in.getStudentBform());
        existing.setMedicalIssues(in.getMedicalIssues());
        existing.setOrphanStatus(in.getOrphanStatus());
        existing.setPreviousSchool(in.getPreviousSchool());
        existing.setPreviousClass(in.getPreviousClass());
        existing.setGuardianName(in.getGuardianName());
        existing.setGuardianRole(in.getGuardianRole());
        existing.setGuardianCnic(in.getGuardianCnic());
        existing.setPhone1(in.getPhone1());
        existing.setPhone2(in.getPhone2());
        existing.setPermanentAddress(in.getPermanentAddress());
        existing.setMailingAddress(in.getMailingAddress());
        existing.setStandardFee(in.getStandardFee());
        existing.setAdmissionFee(in.getAdmissionFee());
        existing.setTuitionDiscount(in.getTuitionDiscount());
        existing.setTransportDiscount(in.getTransportDiscount());
        existing.setSiblingDiscount(in.getSiblingDiscount());
        existing.setTransportMode(in.getTransportMode());
        existing.setTransportType(in.getTransportType());
        existing.setTransportFee(in.getTransportFee());
        existing.setNetPayable(in.getNetPayable());
        existing.setOtherFeesData(in.getOtherFeesData());
        if (in.getStatus() != null) {
            existing.setStatus(in.getStatus());
        }

        // Guarded fields: only overwrite if the incoming value is non-blank,
        // so a save that (for whatever reason) arrives without a photo/certData
        // never erases the one already on file.
        if (!isBlank(in.getPhoto()))    existing.setPhoto(in.getPhoto());
        if (!isBlank(in.getCertData())) existing.setCertData(in.getCertData());
    }

    @GetMapping
    public ResponseEntity<?> getAllStudents(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return ResponseEntity.ok(studentRepository.findBySchoolId(schoolId));
    }

    @GetMapping("/{regNo}")
    public ResponseEntity<?> getStudent(@PathVariable String regNo, @RequestParam String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return studentRepository.findByRegNoAndSchoolId(regNo, schoolId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{regNo}")
    public ResponseEntity<?> deleteStudent(@PathVariable String regNo, @RequestParam String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return studentRepository.findByRegNoAndSchoolId(regNo, schoolId).map(s -> {
            s.setStatus("dropped");
            studentRepository.save(s);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        return Collections.singletonMap("error", message);
    }
}