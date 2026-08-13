package com.softschool.backend.exception;

/**
 * Thrown when a school (directly, or via its plan's default locks) does not
 * have access to the feature the request is trying to use — e.g. calling
 * biometric endpoints on a plan that locks "biometric". Mirrors the same
 * FEATURES/PAGE_FEATURE keys access-control.js already uses client-side
 * ("students", "staff", "attendance", "biometric", "finance", "settings"),
 * so the server now enforces the exact same rule the UI was only hiding.
 */
public class FeatureLockedException extends PlanEnforcementException {

    private final String schoolId;
    private final String featureKey;

    public FeatureLockedException(String schoolId, String featureKey) {
        super("The \"" + featureKey + "\" feature is not available on this school's current plan.");
        this.schoolId = schoolId;
        this.featureKey = featureKey;
    }

    public String getSchoolId() {
        return schoolId;
    }

    public String getFeatureKey() {
        return featureKey;
    }
}
