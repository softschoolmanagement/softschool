package com.softschool.backend.controller;

import com.softschool.backend.model.Plan;
import com.softschool.backend.model.School;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.repository.DropoutStaffRecordRepository;
import com.softschool.backend.repository.FinanceRepository;
import com.softschool.backend.repository.PlanRepository;
import com.softschool.backend.repository.SchoolRepository;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class SuperAdminController {

    @Autowired
    private SchoolRepository schoolRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private FinanceRepository financeRepository;

    @Autowired
    private SchoolSettingsRepository schoolSettingsRepository;

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private DropoutStaffRecordRepository dropoutStaffRecordRepository;

    private static final String SCHOOL_ID_PREFIX = "SS_77";
    private static final String SECURITY_CODE_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String SECURITY_CODE_DIGITS = "0123456789";
    private static final String SECURITY_CODE_CHARS = SECURITY_CODE_LETTERS + SECURITY_CODE_DIGITS;
    private static final int SECURITY_CODE_LENGTH = 7;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object CREATION_LOCK = new Object();

    // Returns every school along with its LIVE student/staff counts (not just
    // the limits) so the super admin panel can show "used / limit" and raise
    // a near-limit alert when either usage crosses 90%. Counts are computed
    // fresh from student/staff tables rather than cached anywhere, since
    // students and staff can be added/removed from each school's own portal
    // at any time.
    @GetMapping("/schools")
    public List<SchoolWithUsage> getAllSchools() {
        return schoolRepository.findAll().stream()
                .map(school -> new SchoolWithUsage(
                        school,
                        studentRepository.countBySchoolId(school.getSchoolId()),
                        staffRepository.countBySchoolId(school.getSchoolId())))
                .collect(Collectors.toList());
    }

    // Wraps a School with its live usage counts. @JsonUnwrapped flattens
    // School's own fields (name, schoolId, studentLimit, staffLimit, etc.)
    // into the same JSON object as studentCount/staffCount, so the frontend
    // keeps reading s.studentLimit etc. exactly as before and just gets two
    // new fields alongside them.
    public static class SchoolWithUsage {
        @com.fasterxml.jackson.annotation.JsonUnwrapped
        public School school;
        public long studentCount;
        public long staffCount;

        public SchoolWithUsage(School school, long studentCount, long staffCount) {
            this.school = school;
            this.studentCount = studentCount;
            this.staffCount = staffCount;
        }
    }

    @GetMapping("/schools/next-id")
    public NextIdResponse previewNextSchoolId() {
        return new NextIdResponse(buildSchoolId(nextSchoolIdSuffix()));
    }

    @PostMapping("/schools")
    public ResponseEntity<?> createSchool(@RequestBody CreateSchoolRequest req) {
        try {
            if (req.getName() == null || req.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(errorBody("School name is required."));
            }

            String cleanName = req.getName().trim();
            String cleanPrefix = req.getPrefix() == null ? null :
                    req.getPrefix().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
            
            if (cleanPrefix != null && cleanPrefix.length() > 4) cleanPrefix = cleanPrefix.substring(0, 4);

            School school = new School();
            school.setName(cleanName);
            school.setPrefix(cleanPrefix);
            school.setPlanId(req.getPlanId() != null ? req.getPlanId().trim() : "default");
            school.setStudentLimit(req.getStudentLimit());
            school.setStaffLimit(req.getStaffLimit());
            school.setLogo(req.getLogo());
            
            // Handle locks safely
            if (req.getLocks() != null && !req.getLocks().isEmpty()) {
                school.setLocks(String.join(",", req.getLocks()));
            }

            LocalDate today = LocalDate.now();
            school.setRegisteredAt(today);
            school.setExpiryDate(today.plusYears(1));
            school.setStatus("active");

            String plainPassword;

            synchronized (CREATION_LOCK) {
                school.setSchoolId(buildSchoolId(nextSchoolIdSuffix()));
                plainPassword = generateSecurityCode();
                school.setPassword(plainPassword);

                try {
                    schoolRepository.save(school);
                } catch (DataIntegrityViolationException e) {
                    school.setSchoolId(buildSchoolId(nextSchoolIdSuffix()));
                    schoolRepository.save(school);
                }
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(
                    new SchoolCredentialsResponse(school, plainPassword));

        } catch (Exception e) {
            // THIS WILL PRINT THE ACTUAL ERROR IN YOUR TERMINAL
            System.err.println("CRITICAL ERROR DURING SCHOOL CREATION:");
            e.printStackTrace(); 
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorBody("Internal Error: " + e.getMessage()));
        }
    }

    // --- UPDATED REQUEST CLASSES WITH GETTERS/SETTERS (Jackson needs these) ---

    public static class CreateSchoolRequest {
        private String name;
        private String prefix;
        private String planId;
        private Integer studentLimit;
        private Integer staffLimit;
        private List<String> locks;
        private String logo;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getPlanId() { return planId; }
        public void setPlanId(String planId) { this.planId = planId; }
        public Integer getStudentLimit() { return studentLimit; }
        public void setStudentLimit(Integer studentLimit) { this.studentLimit = studentLimit; }
        public Integer getStaffLimit() { return staffLimit; }
        public void setStaffLimit(Integer staffLimit) { this.staffLimit = staffLimit; }
        public List<String> getLocks() { return locks; }
        public void setLocks(List<String> locks) { this.locks = locks; }
        public String getLogo() { return logo; }
        public void setLogo(String logo) { this.logo = logo; }
    }

    // ... (Keep other helper methods like buildSchoolId, nextSchoolIdSuffix, etc. exactly as they were)
    
    private int nextSchoolIdSuffix() {
        Integer max = schoolRepository.findMaxSchoolIdSuffix(SCHOOL_ID_PREFIX);
        return (max == null ? 0 : max) + 1;
    }

    private String buildSchoolId(int suffix) {
        return SCHOOL_ID_PREFIX + "_" + suffix;
    }

    // Generates a 7-char code that ALWAYS contains at least one lowercase
    // letter and at least one digit (the login page's registration form
    // requires both — see CODE_RE in index.js). A purely random pick from
    // the combined alphabet can land on an all-letters code roughly 1 in 10
    // times, which would then fail that validation, so we guarantee the mix
    // explicitly instead of leaving it to chance.
    private String generateSecurityCode() {
        char[] code = new char[SECURITY_CODE_LENGTH];

        // 1. Force at least one letter and one digit, at two distinct positions.
        int letterPos = RANDOM.nextInt(SECURITY_CODE_LENGTH);
        int digitPos;
        do {
            digitPos = RANDOM.nextInt(SECURITY_CODE_LENGTH);
        } while (digitPos == letterPos);

        code[letterPos] = SECURITY_CODE_LETTERS.charAt(RANDOM.nextInt(SECURITY_CODE_LETTERS.length()));
        code[digitPos] = SECURITY_CODE_DIGITS.charAt(RANDOM.nextInt(SECURITY_CODE_DIGITS.length()));

        // 2. Fill the remaining positions from the full alphabet (letters + digits).
        for (int i = 0; i < SECURITY_CODE_LENGTH; i++) {
            if (i == letterPos || i == digitPos) continue;
            code[i] = SECURITY_CODE_CHARS.charAt(RANDOM.nextInt(SECURITY_CODE_CHARS.length()));
        }

        return new String(code);
    }

    private java.util.Map<String, String> errorBody(String message) {
        return java.util.Collections.singletonMap("error", message);
    }

    public static class NextIdResponse {
        public String schoolId;
        public NextIdResponse(String schoolId) { this.schoolId = schoolId; }
    }

    public static class SchoolCredentialsResponse {
        public Long id;
        public String schoolId;
        public String name;
        public String password;
        public SchoolCredentialsResponse(School school, String plainPassword) {
            this.id = school.getId();
            this.schoolId = school.getSchoolId();
            this.name = school.getName();
            this.password = plainPassword;
        }
    }

    @Autowired
private PlanRepository planRepository; // 1. Add this injection

// 2. Add these Endpoints:

@GetMapping("/plans")
public List<Plan> getAllPlans() {
    return planRepository.findAll();
}

@PostMapping("/plans")
public ResponseEntity<?> createPlan(@RequestBody Plan plan) {
    if (planRepository.existsById(plan.getId())) {
        return ResponseEntity.badRequest().body(errorBody("A plan with this ID already exists."));
    }
    Plan saved = planRepository.save(plan);
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
}

@DeleteMapping("/plans/{id}")
public ResponseEntity<?> deletePlan(@PathVariable String id) {
    planRepository.deleteById(id);
    return ResponseEntity.noContent().build();
}

@PutMapping("/plans/{id}")
public ResponseEntity<?> updatePlan(@PathVariable String id, @RequestBody Plan plan) {
    return planRepository.findById(id).map(existing -> {
        existing.setLabel(plan.getLabel());
        existing.setPrice(plan.getPrice());
        existing.setStudentLimit(plan.getStudentLimit());
        existing.setStaffLimit(plan.getStaffLimit());
        existing.setLocks(plan.getLocks());
        Plan saved = planRepository.save(existing);

        // Cascade: every school currently on this plan automatically picks up
        // the plan's new student limit, staff limit, and locked
        // features, overriding any per-school customization they had before.
        List<School> schoolsOnPlan = schoolRepository.findByPlanId(id);
        for (School school : schoolsOnPlan) {
            school.setStudentLimit(saved.getStudentLimit());
            school.setStaffLimit(saved.getStaffLimit());
            school.setLocks(saved.getLocks());
        }
        schoolRepository.saveAll(schoolsOnPlan);

        return ResponseEntity.ok(saved);
    }).orElse(ResponseEntity.notFound().build());
}

// 1. Update School Details
    @Transactional
    @PutMapping("/schools/{id}")
    public ResponseEntity<?> updateSchool(@PathVariable Long id, @RequestBody CreateSchoolRequest req) {
        return schoolRepository.findById(id).map(school -> {
            // Captured BEFORE overwriting, so we can tell below whether the
            // Registration prefix actually changed and — if so — cascade that
            // change onto every student/staff record this school already has.
            String oldPrefix = school.getPrefix();
            String newPrefix = req.getPrefix();

            school.setName(req.getName());
            school.setPrefix(newPrefix);
            school.setPlanId(req.getPlanId());
            school.setStudentLimit(req.getStudentLimit());
            school.setStaffLimit(req.getStaffLimit());

            // IMPORTANT: only touch the logo if the request actually included one.
            // The frontend omits "logo" from the JSON entirely when the user
            // didn't change it on the Manage School screen, so req.getLogo()
            // comes back null on every unrelated save (rename, plan change,
            // renew, block/unblock). Unconditionally calling setLogo(null)
            // here is what was wiping out the school's logo over time.
            // An explicit removal sends an empty string "", which is still
            // != null, so removal still works correctly.
            if (req.getLogo() != null) {
                school.setLogo(req.getLogo());
            }

            if (req.getLocks() != null) {
                school.setLocks(String.join(",", req.getLocks()));
            }
            
            schoolRepository.save(school);

            // ── PREFIX CHANGE CASCADE ────────────────────────────────────
            // Previously, changing a school's Registration prefix here only
            // updated the School row. The frontend's regNo/staffId generator
            // (manage-students.js#generateNextRegistrationNumber and
            // access-control.js#nextStaffId) always scans for the CURRENT
            // prefix only, so every already-admitted student/staff member —
            // still carrying the OLD prefix — became invisible to that scan
            // and the very next admission restarted the sequence at 1 under
            // the new prefix, instead of continuing on from the last one.
            //
            // Fix: when the prefix actually changes, re-stamp every existing
            // student's regNo and every existing staff member's staffId for
            // THIS school with the new prefix, keeping each record's original
            // sequence number intact (e.g. "PSC_7" -> "NEW_7"). After that,
            // the frontend's existing "scan for the highest number already
            // used with the current prefix" logic naturally continues the
            // count from the highest renamed number — no frontend changes,
            // and no other existing functionality touched.
            if (newPrefix != null && !newPrefix.trim().isEmpty() && !newPrefix.trim().equalsIgnoreCase(oldPrefix)) {
                cascadePrefixChange(school.getSchoolId(), newPrefix.trim().toUpperCase(Locale.ROOT));
            }

            return ResponseEntity.ok(school);
        }).orElse(ResponseEntity.notFound().build());
    }

    // CURRENT regNo shape: "..._<YY>_<digits>" (e.g. "PSC_26_7") — the
    // frontend now bakes the 2-digit admission year into every regNo. This
    // captures the "YY_digits" tail so a rename only swaps the prefix text
    // and keeps both the year AND the sequence number intact.
    private static final java.util.regex.Pattern REG_NO_YEAR_SEQUENCE =
            java.util.regex.Pattern.compile("^.*?_(\\d{2}_\\d+)$");

    // LEGACY regNo shape from before the year segment existed: plain
    // "..._<digits>" (e.g. "PSC_7"). Kept so schools with older records
    // (registered before this feature) still rename cleanly instead of
    // being skipped.
    private static final java.util.regex.Pattern REG_NO_SEQUENCE =
            java.util.regex.Pattern.compile("^.*?_(\\d+)$");

    // CURRENT staffId shape: "..._<YY>_S_<digits>" (e.g. "PSC_26_S_3").
    private static final java.util.regex.Pattern STAFF_ID_YEAR_SEQUENCE =
            java.util.regex.Pattern.compile("^.*?_(\\d{2}_S_\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    // LEGACY staffId shape: "..._S_<digits>" (e.g. "PSC_S_3").
    private static final java.util.regex.Pattern STAFF_ID_SEQUENCE =
            java.util.regex.Pattern.compile("^.*?_S_(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Re-stamps every existing student regNo / staff staffId for a school
     * with {@code newPrefix}, preserving each record's original sequence
     * number, and follows each rename through to every OTHER table that
     * stores that regNo/staffId as a plain string (Finance fee
     * ledgers/fines/salaries/advances, Attendance history) so nothing gets
     * silently orphaned. Records that don't match the expected
     * "..._<digits>" (or "..._S_<digits>") shape are left untouched rather
     * than guessed at.
     */
    private void cascadePrefixChange(String schoolId, String newPrefix) {
        List<com.softschool.backend.model.Student> students = studentRepository.findBySchoolId(schoolId);
        for (com.softschool.backend.model.Student s : students) {
            String oldRegNo = s.getRegNo();
            if (oldRegNo == null) continue;

            String newRegNo = null;
            java.util.regex.Matcher ym = REG_NO_YEAR_SEQUENCE.matcher(oldRegNo);
            if (ym.matches()) {
                newRegNo = newPrefix + "_" + ym.group(1); // preserves "YY_N"
            } else {
                java.util.regex.Matcher lm = REG_NO_SEQUENCE.matcher(oldRegNo);
                if (lm.matches()) {
                    newRegNo = newPrefix + "_" + lm.group(1); // legacy "N" only
                }
            }
            if (newRegNo == null || newRegNo.equals(oldRegNo)) continue;

            s.setRegNo(newRegNo);

            // Follow the rename into finance (fee ledgers/fines) and
            // attendance history so a renamed student's records stay
            // attached to them instead of pointing at a regNo that no
            // longer exists.
            List<com.softschool.backend.model.Finance> financeRows =
                    financeRepository.findBySchoolIdAndRegNo(schoolId, oldRegNo);
            for (com.softschool.backend.model.Finance f : financeRows) {
                f.setRegNo(newRegNo);
            }
            financeRepository.saveAll(financeRows);

            List<com.softschool.backend.model.Attendance> attendanceRows =
                    attendanceRepository.findByMemberIdAndSchoolIdOrderByDateDesc(oldRegNo, schoolId);
            for (com.softschool.backend.model.Attendance a : attendanceRows) {
                a.setMemberId(newRegNo);
            }
            attendanceRepository.saveAll(attendanceRows);
        }
        studentRepository.saveAll(students);

        List<com.softschool.backend.model.Staff> staffMembers = staffRepository.findBySchoolId(schoolId);
        for (com.softschool.backend.model.Staff st : staffMembers) {
            String oldStaffId = st.getStaffId();
            if (oldStaffId == null) continue;

            String newStaffId = null;
            java.util.regex.Matcher ym = STAFF_ID_YEAR_SEQUENCE.matcher(oldStaffId);
            if (ym.matches()) {
                newStaffId = newPrefix + "_" + ym.group(1); // preserves "YY_S_N"
            } else {
                java.util.regex.Matcher lm = STAFF_ID_SEQUENCE.matcher(oldStaffId);
                if (lm.matches()) {
                    newStaffId = newPrefix + "_S_" + lm.group(1); // legacy "S_N" only
                }
            }
            if (newStaffId == null || newStaffId.equals(oldStaffId)) continue;

            st.setStaffId(newStaffId);

            List<com.softschool.backend.model.Finance> financeRows =
                    financeRepository.findBySchoolIdAndStaffId(schoolId, oldStaffId);
            for (com.softschool.backend.model.Finance f : financeRows) {
                f.setStaffId(newStaffId);
            }
            financeRepository.saveAll(financeRows);

            List<com.softschool.backend.model.Attendance> attendanceRows =
                    attendanceRepository.findByMemberIdAndSchoolIdOrderByDateDesc(oldStaffId, schoolId);
            for (com.softschool.backend.model.Attendance a : attendanceRows) {
                a.setMemberId(newStaffId);
            }
            attendanceRepository.saveAll(attendanceRows);
        }
        staffRepository.saveAll(staffMembers);
    }

    // 2. Change Status (Block/Unblock)
    @PutMapping("/schools/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable Long id, @RequestBody java.util.Map<String, String> statusMap) {
        return schoolRepository.findById(id).map(school -> {
            school.setStatus(statusMap.get("status"));
            schoolRepository.save(school);
            return ResponseEntity.ok(school);
        }).orElse(ResponseEntity.notFound().build());
    }

    // 3. Renew School (Add 1 Year)
    @PutMapping("/schools/{id}/renew")
    public ResponseEntity<?> renewSchool(@PathVariable Long id) {
        return schoolRepository.findById(id).map(school -> {
            LocalDate currentExpiry = school.getExpiryDate();
            // If already expired, renew from today. If not, add to existing date.
            LocalDate startDate = (currentExpiry.isBefore(LocalDate.now())) ? LocalDate.now() : currentExpiry;
            school.setExpiryDate(startDate.plusYears(1));
            schoolRepository.save(school);
            return ResponseEntity.ok(school);
        }).orElse(ResponseEntity.notFound().build());
    }

    // 4. Delete School
    // Permanent delete = wipes the school's own students, all its finance
    // records (fee ledgers, fines, salaries, advances), its settings row
    // (class fees, late fee rules, pay variables, attendance timing), its
    // attendance history, its staff (teaching + non-teaching), AND its
    // archived dropout-staff records (lifetime salary/bonus/fine totals for
    // staff who were deleted while this school existed — see
    // DropoutStaffRecord's class docs). Without clearing this table too, a
    // deleted school's schoolId can be reissued to a brand-new school (see
    // createSchool below), and that new school would instantly "inherit"
    // the old school's dropout-staff salary/fine totals into its own
    // dashboard, because DropoutStaffRecord rows are looked up purely by
    // schoolId with no link back to the (now-deleted) School row itself.
    // This is deliberately different from block/unblock (setStatus above),
    // which only flips School.status and never touches students, staff,
    // finance, settings, attendance, or dropout-staff data — a blocked
    // school's records are kept intact in case it's unblocked later.
    @Transactional
    @DeleteMapping("/schools/{id}")
    public ResponseEntity<?> deleteSchool(@PathVariable Long id) {
        return schoolRepository.findById(id).map(school -> {
            studentRepository.deleteBySchoolId(school.getSchoolId());
            staffRepository.deleteBySchoolId(school.getSchoolId());
            financeRepository.deleteBySchoolId(school.getSchoolId());
            schoolSettingsRepository.deleteBySchoolId(school.getSchoolId());
            attendanceRepository.deleteBySchoolId(school.getSchoolId());
            dropoutStaffRecordRepository.deleteBySchoolId(school.getSchoolId());
            schoolRepository.delete(school);
            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }
}