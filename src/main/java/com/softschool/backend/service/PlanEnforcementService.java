package com.softschool.backend.service;

import com.softschool.backend.exception.FeatureLockedException;
import com.softschool.backend.exception.PlanLimitExceededException;
import com.softschool.backend.exception.SchoolAccessException;
import com.softschool.backend.model.Plan;
import com.softschool.backend.model.School;
import com.softschool.backend.repository.PlanRepository;
import com.softschool.backend.repository.SchoolRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Server-side mirror of access-control.js's PLANS / FEATURES / lock logic.
 *
 * access-control.js hides locked nav items and blocks over-limit adds in
 * the browser only — nothing stops a client from calling the REST API
 * directly to use a "locked" feature or exceed a plan's student/staff cap
 * (Security Audit finding #2). This service is the single place every
 * controller asks "is this school actually allowed to do that?" before
 * performing the action, so the rule is enforced no matter how the request
 * arrives.
 *
 * NOTE on scope: this closes the "UI-only lock" gap specifically. It still
 * trusts the schoolId the caller supplies, because the API currently has no
 * authenticated session tying a request to a school (Security Audit finding
 * #1 — no auth on /api/students, /api/staff, /api/finance, etc.). Once that
 * authentication layer exists, schoolId here should come from the
 * authenticated principal instead of a request parameter, exactly like
 * AdminAuthFilter already does for /api/admin/**.
 */
@Service
public class PlanEnforcementService {

    /** Every lockable feature key — must stay in sync with FEATURES in access-control.js. */
    public static final String FEATURE_STUDENTS = "students";
    public static final String FEATURE_STAFF = "staff";
    public static final String FEATURE_ATTENDANCE = "attendance";
    public static final String FEATURE_BIOMETRIC = "biometric";
    public static final String FEATURE_FINANCE = "finance";
    public static final String FEATURE_SETTINGS = "settings";

    @Autowired private SchoolRepository schoolRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private StaffRepository staffRepository;

    // ── PUBLIC ENTRY POINTS ─────────────────────────────────────────

    /**
     * Loads the school, confirms it's active and not expired, and confirms
     * the given feature isn't locked. Throws SchoolAccessException or
     * FeatureLockedException; callers don't need to catch anything —
     * PlanEnforcementExceptionHandler turns it into the right HTTP response.
     */
    public School requireFeature(String schoolId, String featureKey) {
        School school = loadActiveSchool(schoolId);
        if (resolveLocks(school).contains(featureKey)) {
            throw new FeatureLockedException(schoolId, featureKey);
        }
        return school;
    }

    /**
     * Like requireFeature, but also enforces the student-count limit for a
     * NEW student being added. Call this before persisting a brand-new
     * student row (skip it for edits to an existing student — an edit
     * doesn't change the headcount).
     */
    public School requireCapacityForNewStudent(String schoolId) {
        School school = requireFeature(schoolId, FEATURE_STUDENTS);
        int limit = resolveStudentLimit(school);
        long current = studentRepository.countBySchoolId(schoolId);
        if (limit >= 0 && current >= limit) {
            throw new PlanLimitExceededException(schoolId, "students", limit, current);
        }
        return school;
    }

    /**
     * Like requireFeature, but also enforces the staff-count limit for a
     * NEW staff member being added.
     */
    public School requireCapacityForNewStaff(String schoolId) {
        School school = requireFeature(schoolId, FEATURE_STAFF);
        int limit = resolveStaffLimit(school);
        long current = staffRepository.countBySchoolId(schoolId);
        if (limit >= 0 && current >= limit) {
            throw new PlanLimitExceededException(schoolId, "staff", limit, current);
        }
        return school;
    }

    // ── RESOLUTION LOGIC (mirrors access-control.js) ────────────────

    /** Loads the school and checks status/expiry; does NOT check feature locks. */
    public School loadActiveSchool(String schoolId) {
        if (schoolId == null || schoolId.trim().isEmpty()) {
            throw new SchoolAccessException(schoolId, SchoolAccessException.Reason.NOT_FOUND);
        }
        School school = schoolRepository.findBySchoolId(schoolId)
                .orElseThrow(() -> new SchoolAccessException(schoolId, SchoolAccessException.Reason.NOT_FOUND));

        if ("blocked".equalsIgnoreCase(school.getStatus())) {
            throw new SchoolAccessException(schoolId, SchoolAccessException.Reason.BLOCKED);
        }
        if (school.getExpiryDate() != null && school.getExpiryDate().isBefore(LocalDate.now())) {
            throw new SchoolAccessException(schoolId, SchoolAccessException.Reason.EXPIRED);
        }
        return school;
    }

    /**
     * A feature is locked if it's in the school's own `locks` override, OR
     * (when the school has no override of its own) in the plan's default
     * locks — matching PLANS[planId].defaultLocks in access-control.js.
     */
    public Set<String> resolveLocks(School school) {
        if (school.getLocks() != null && !school.getLocks().trim().isEmpty()) {
            return splitCsv(school.getLocks());
        }
        Plan plan = school.getPlanId() == null ? null : planRepository.findById(school.getPlanId()).orElse(null);
        if (plan != null && plan.getLocks() != null && !plan.getLocks().trim().isEmpty()) {
            return splitCsv(plan.getLocks());
        }
        return Collections.emptySet();
    }

    /** School.studentLimit overrides Plan.studentLimit; -1/null means "no limit". */
    public int resolveStudentLimit(School school) {
        if (school.getStudentLimit() != null) return school.getStudentLimit();
        Plan plan = school.getPlanId() == null ? null : planRepository.findById(school.getPlanId()).orElse(null);
        return plan != null && plan.getStudentLimit() != null ? plan.getStudentLimit() : -1;
    }

    /** School.staffLimit overrides Plan.staffLimit; -1/null means "no limit". */
    public int resolveStaffLimit(School school) {
        if (school.getStaffLimit() != null) return school.getStaffLimit();
        Plan plan = school.getPlanId() == null ? null : planRepository.findById(school.getPlanId()).orElse(null);
        return plan != null && plan.getStaffLimit() != null ? plan.getStaffLimit() : -1;
    }

    private Set<String> splitCsv(String csv) {
        Set<String> result = new HashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
