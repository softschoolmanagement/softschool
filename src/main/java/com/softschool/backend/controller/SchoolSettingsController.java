package com.softschool.backend.controller;

import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Student;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.repository.StudentRepository;
import com.softschool.backend.service.PlanEnforcementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller backing the Settings page (settings.html / settings.js).
 * Every route is scoped to a single school via {schoolId} in the path
 * (School.schoolId, e.g. "SS_77_12") — settings are no longer a single
 * global row.
 *
 *   GET   /api/settings/{schoolId}         -> load everything (School Info tab wires all fields at once)
 *   PUT   /api/settings/{schoolId}         -> "Save All" button (school info + classes + late fee + pay variables)
 *   PUT   /api/settings/{schoolId}/timing  -> "Save Timings" button (Attendance Timing tab, saved separately)
 *   GET   /api/settings/{schoolId}/classes -> just the class fee structure grid
 *   POST  /api/settings/{schoolId}/reset   -> "Reset" button (restores defaults for that school)
 *
 * NOTE: settings.js on the frontend needs to be updated to include the
 * logged-in school's schoolId in these URLs (it's already available from
 * the login/registration flow — see School.schoolId in School.java).
 */
@RestController
@RequestMapping("/api/settings")
public class SchoolSettingsController {

    private final SchoolSettingsRepository repository;
    private final StudentRepository studentRepository;
    private final PlanEnforcementService planEnforcementService;

    @Autowired
    public SchoolSettingsController(
            SchoolSettingsRepository repository,
            StudentRepository studentRepository,
            PlanEnforcementService planEnforcementService) {
        this.repository = repository;
        this.studentRepository = studentRepository;
        this.planEnforcementService = planEnforcementService;
    }

    /** GET /api/settings/{schoolId} — returns that school's settings row, creating defaults if none exist yet. */
    @GetMapping("/{schoolId}")
    public ResponseEntity<SchoolSettings> getSettings(@PathVariable String schoolId) {
        SchoolSettings settings = repository.findBySchoolId(schoolId)
                .orElseGet(() -> {
                    SchoolSettings fresh = new SchoolSettings();
                    fresh.setSchoolId(schoolId);
                    return repository.save(fresh);
                });
        return ResponseEntity.ok(settings);
    }

    /**
     * PUT /api/settings/{schoolId} — "Save All": school info, class fee structure
     * (+ sections), late fee rules and global pay variables, for this school.
     */
    @Transactional
    @PutMapping("/{schoolId}")
    public ResponseEntity<SchoolSettings> saveAll(@PathVariable String schoolId, @RequestBody SchoolSettings incoming) {
        // "Admin Settings" is itself a lockable feature in access-control.js —
        // previously only the nav link was hidden client-side (Security
        // Audit #2).
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_SETTINGS);
        SchoolSettings settings = repository.findBySchoolId(schoolId).orElseGet(SchoolSettings::new);
        settings.setSchoolId(schoolId);

        /*
         * Class fees are the standard fee profile for every student in that
         * class. Keep the old values before replacing the settings collection
         * so a fee edit can be applied to existing student rows as part of the
         * same transaction.
         *
         * This intentionally does not touch generated voucher records. Finance
         * stores a voucher snapshot for each generated month; the finance page
         * uses that snapshot for an already-generated month and reads the
         * student's new standardFee when the next month is generated.
         */
        Map<String, Double> previousFees = classFeeMap(settings.getClasses());
        List<SchoolSettings.ClassFee> incomingClasses =
                incoming.getClasses() == null
                        ? Collections.emptyList()
                        : incoming.getClasses();

        // School info
        settings.setSchoolName(incoming.getSchoolName());
        settings.setSchoolAddress(incoming.getSchoolAddress());
        settings.setSchoolPhone(incoming.getSchoolPhone());
        settings.setSchoolPhoneAlt(incoming.getSchoolPhoneAlt());
        settings.setSchoolEmail(incoming.getSchoolEmail());
        settings.setSchoolWebsite(incoming.getSchoolWebsite());
        settings.setSchoolPrincipal(incoming.getSchoolPrincipal());
        settings.setSchoolRegNo(incoming.getSchoolRegNo());

        // Late fee
        settings.setLateFeeEnabled(incoming.getLateFeeEnabled());
        settings.setLateFeeDeadlineDay(incoming.getLateFeeDeadlineDay());
        settings.setLateFeeType(incoming.getLateFeeType());
        settings.setLateFeeAmount(incoming.getLateFeeAmount());
        settings.setLateFeeGrace(incoming.getLateFeeGrace());

        // Pay variables
        settings.setPayPenaltyType(incoming.getPayPenaltyType());
        settings.setPayPenaltyValue(incoming.getPayPenaltyValue());
        settings.setPayBonus(incoming.getPayBonus());

        // Classes (replace entire list, mirrors frontend's saveAll() rebuild)
        settings.setClasses(incomingClasses);

        syncExistingStudentFees(schoolId, previousFees, incomingClasses);

        return ResponseEntity.ok(repository.save(settings));
    }

    /**
     * Applies changed standard fees to all existing student records in the
     * matching class. Discounts and other financial fields are preserved, but
     * netPayable is recalculated because it is the student's first-month
     * payable amount:
     *
     * standard fee + admission fee + transport fee
     * - tuition discount - transport discount - sibling discount
     *
     * Matching is case-insensitive and whitespace-tolerant so a harmless
     * capitalization difference in Settings cannot leave students stale.
     */
    private void syncExistingStudentFees(
            String schoolId,
            Map<String, Double> previousFees,
            List<SchoolSettings.ClassFee> incomingClasses) {
        Map<String, Double> changedFees = new HashMap<>();

        for (SchoolSettings.ClassFee classFee : incomingClasses) {
            if (classFee == null || isBlank(classFee.getClassName())) {
                continue;
            }

            String classKey = normalize(classFee.getClassName());
            double newFee = safeAmount(classFee.getFee());
            Double oldFee = previousFees.get(classKey);

            // A newly-added class can still have existing imported students,
            // so it is treated as a fee change from the implicit zero value.
            if (oldFee == null || Double.compare(oldFee, newFee) != 0) {
                changedFees.put(classKey, newFee);
            }
        }

        if (changedFees.isEmpty()) {
            return;
        }

        List<Student> students = studentRepository.findBySchoolId(schoolId);
        boolean changed = false;

        for (Student student : students) {
            if (student == null || isBlank(student.getStudentClass())) {
                continue;
            }

            Double newFee = changedFees.get(normalize(student.getStudentClass()));
            if (newFee == null
                    || (student.getStandardFee() != null
                        && Double.compare(student.getStandardFee(), newFee) == 0)) {
                continue;
            }

            student.setStandardFee(newFee);
            student.setNetPayable(calculateFirstMonthNetPayable(student, newFee));
            changed = true;
        }

        if (changed) {
            studentRepository.saveAll(students);
        }
    }

    private Map<String, Double> classFeeMap(List<SchoolSettings.ClassFee> classes) {
        Map<String, Double> fees = new HashMap<>();
        if (classes == null) {
            return fees;
        }

        for (SchoolSettings.ClassFee classFee : classes) {
            if (classFee != null && !isBlank(classFee.getClassName())) {
                fees.put(normalize(classFee.getClassName()), safeAmount(classFee.getFee()));
            }
        }
        return fees;
    }

    private double calculateFirstMonthNetPayable(Student student, double standardFee) {
        return Math.max(
                0,
                standardFee
                        + safeAmount(student.getAdmissionFee())
                        + safeAmount(student.getTransportFee())
                        - safeAmount(student.getTuitionDiscount())
                        - safeAmount(student.getTransportDiscount())
                        - safeAmount(student.getSiblingDiscount()));
    }

    private double safeAmount(Double value) {
        return value == null ? 0D : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** PUT /api/settings/{schoolId}/timing — "Save Timings" on the Attendance Timing tab. */
    @PutMapping("/{schoolId}/timing")
    public ResponseEntity<SchoolSettings> saveTiming(@PathVariable String schoolId, @RequestBody SchoolSettings incoming) {
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_SETTINGS);
        SchoolSettings settings = repository.findBySchoolId(schoolId).orElseGet(SchoolSettings::new);
        settings.setSchoolId(schoolId);

        settings.setAutosave1Hour(incoming.getAutosave1Hour());
        settings.setAutosave1Minute(incoming.getAutosave1Minute());
        settings.setAutosave1Meridiem(incoming.getAutosave1Meridiem());
        settings.setAutosave1Enabled(incoming.getAutosave1Enabled());

        settings.setAutosave2Hour(incoming.getAutosave2Hour());
        settings.setAutosave2Minute(incoming.getAutosave2Minute());
        settings.setAutosave2Meridiem(incoming.getAutosave2Meridiem());
        settings.setAutosave2Enabled(incoming.getAutosave2Enabled());

        return ResponseEntity.ok(repository.save(settings));
    }

    /** GET /api/settings/{schoolId}/classes — just the class fee structure grid, if you need it standalone. */
    @GetMapping("/{schoolId}/classes")
    public ResponseEntity<List<SchoolSettings.ClassFee>> getClasses(@PathVariable String schoolId) {
        SchoolSettings settings = repository.findBySchoolId(schoolId).orElseGet(SchoolSettings::new);
        return ResponseEntity.ok(settings.getClasses());
    }

    /** POST /api/settings/{schoolId}/reset — "Reset" button: wipes this school's row back to entity defaults. */
    @PostMapping("/{schoolId}/reset")
    public ResponseEntity<SchoolSettings> reset(@PathVariable String schoolId) {
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_SETTINGS);
        repository.deleteBySchoolId(schoolId);
        SchoolSettings fresh = new SchoolSettings();
        fresh.setSchoolId(schoolId);
        return ResponseEntity.ok(repository.save(fresh));
    }
}