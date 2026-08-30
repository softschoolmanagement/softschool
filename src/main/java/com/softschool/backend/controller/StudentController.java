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
        existing.setIsLifetime(in.getIsLifetime());
        existing.setDiscountExpiry(in.getDiscountExpiry());
        if (in.getStatus() != null) {
            existing.setStatus(in.getStatus());
        }

        // Graduation snapshot (see Student.graduatedDate for why this needs
        // to be copied explicitly, same reasoning as droppedDate below).
        existing.setGraduatedDate(in.getGraduatedDate());
        existing.setGraduatedYear(in.getGraduatedYear());
        existing.setGraduatedClass(in.getGraduatedClass());
        existing.setGraduatedSection(in.getGraduatedSection());

        // Sibling link fields — without copying these here, marking the
        // ORIGINAL (already-admitted) student as part of a sibling group
        // silently failed to persist: the entity gained the columns, but an
        // update to an existing student only ever passed through this
        // whitelist, which never listed them.
        existing.setSiblingGroupId(in.getSiblingGroupId());
        existing.setIsSibling(in.getIsSibling());
        existing.setSiblingOf(in.getSiblingOf());
        existing.setHasSiblings(in.getHasSiblings());

        // Archive Center "Date Removed" — see Student.droppedDate for why
        // this needs to be copied explicitly, same reasoning as the sibling
        // fields above.
        existing.setDroppedDate(in.getDroppedDate());

        // Voucher / arrears state (manage-finance.js) — see Student.arrears
        // for why these need to be copied explicitly, same reasoning as the
        // sibling/droppedDate fields above: adding the columns alone does
        // nothing unless an update actually overlays them onto the managed
        // row here.
        existing.setArrears(in.getArrears());
        existing.setVoucherCustomFees(in.getVoucherCustomFees());
        existing.setVoucherCustomFeesMonth(in.getVoucherCustomFeesMonth());
        existing.setVoucherBulkDiscount(in.getVoucherBulkDiscount());
        existing.setVoucherNote(in.getVoucherNote());

        // Guarded fields: only overwrite if the incoming value is non-blank,
        // so a save that (for whatever reason) arrives without a photo/certData
        // never erases the one already on file.
        if (!isBlank(in.getPhoto()))    existing.setPhoto(in.getPhoto());
        if (!isBlank(in.getCertData())) existing.setCertData(in.getCertData());
    }

    /**
     * BUGFIX — "arrears/voucher edits never persist": manage-finance.js's
     * saveStudentsCache() has always PUT-ed the whole roster to this exact
     * URL ({@code PUT /api/students} with body {@code {items:[...],
     * schoolId}}) every time it edits arrears, a voucher note, or a custom
     * voucher breakdown. No handler existed for it — only POST "" (create
     * one) and PUT "/{regNo}" (update one) did — so every one of those
     * saves 404'd silently (_backendSave only console.warns on a failed
     * request) and never reached the database at all.
     *
     * Upserts each item through the same existing-row-merge logic as the
     * single-student endpoints above (never a delete-then-recreate of the
     * whole table, which would be destructive for roster data and would
     * mint new IDs). Unknown/malformed items are skipped rather than
     * failing the whole batch, matching FinanceController#bulkSave's
     * tolerance.
     */
    @PutMapping({"", "/"})
    public ResponseEntity<?> bulkSaveStudents(@RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) return badRequest("Request body is required.");
        String schoolId = payload.get("schoolId") != null ? payload.get("schoolId").toString() : null;
        if (isBlank(schoolId)) return badRequest("schoolId is required.");

        Object itemsObj = payload.get("items");
        if (!(itemsObj instanceof java.util.List)) return badRequest("items array is required.");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Object item : (java.util.List<?>) itemsObj) {
            try {
                Student incoming = mapper.convertValue(item, Student.class);
                if (isBlank(incoming.getSchoolId())) incoming.setSchoolId(schoolId);
                if (isBlank(incoming.getSchoolId())) continue; // still no schoolId — skip rather than fail the batch
                persistStudent(incoming);
            } catch (Exception e) {
                // Skip a single malformed item rather than failing the whole batch —
                // matches FinanceController#bulkSave's per-item tolerance.
            }
        }

        return ResponseEntity.ok(studentRepository.findBySchoolId(schoolId));
    }

    @GetMapping
    public ResponseEntity<?> getAllStudents(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return ResponseEntity.ok(studentRepository.findBySchoolId(schoolId));
    }

    /**
     * PERFORMANCE FIX — the main dashboard's Student attendance card
     * (main.js's calculateFinancials -> loadAttendanceData) used to call
     * plain GET /api/students, which drags every student's base64
     * photo/certData/otherFeesData LONGTEXT blob along with it (Hibernate
     * fetches @Lob string columns eagerly here) even though the dashboard
     * only ever reads regNo/status/admissionDate/admissionFee. For a school
     * with a few hundred students with photos on file, that's megabytes of
     * JSON parsed and thrown away on every single dashboard load.
     *
     * This lightweight endpoint (see StudentSummaryDTO / the JPQL
     * projection in StudentRepository) selects only those 4 columns at the
     * SQL level, so the LOB columns never get read off disk or sent over
     * the wire for this call. Full student records (with photos) are still
     * served as before by GET /api/students, for Manage Students.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getStudentsSummary(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return ResponseEntity.ok(studentRepository.findSummaryBySchoolId(schoolId));
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